package com.huawei.ais.demo.moderation.ext;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import com.huawei.ais.demo.moderation.model.JobMetaInfo;
import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpResponse;
import org.apache.http.util.EntityUtils;

import com.huawei.ais.demo.HttpJsonDataUtils;
import com.huawei.ais.demo.moderation.Config;
import com.huawei.ais.demo.moderation.model.SubmitReq;
import com.huawei.ais.demo.moderation.model.SubmitSuccessRes;
import com.huawei.ais.demo.obs.ObsFileHandle;
import com.huawei.ais.demo.obs.SimpleObsClient;
import com.huawei.ais.sdk.AisAccess;

class SubmitJobTask implements Callable<String> {

    private static final Log LOGGER = LogFactory.getLog(SubmitJobTask.class);

    private static final String SUBMIT_JOB_URI = "/v1.0/moderation/video";
    private static final String JSON_ROOT = "result";

    private static final Config CONFIG = Config.getInstance();

    private String videoUrl;
    private String callbackUrl;
    private AisAccess aisAccessClient;
    private SimpleObsClient simpleObsClient;
    private ExecutorService callbackExecutors;
    private JobMetaInfo jobMetaInfo;

    SubmitJobTask(String videoUrl,JobMetaInfo jobMetaInfo, String callbackUrl, AisAccess aisAccessClient, SimpleObsClient simpleObsClient,
                  ExecutorService callbackExecutors) {
        this.videoUrl = videoUrl;
        this.jobMetaInfo = jobMetaInfo;
        this.callbackUrl = callbackUrl;
        this.aisAccessClient = aisAccessClient;
        this.simpleObsClient = simpleObsClient;
        this.callbackExecutors = callbackExecutors;
    }

    @Override
    public String call() throws IOException {
        String filePath = downloadVideo(videoUrl);
        ObsFileHandle obsFileHandle = simpleObsClient.uploadFile(CONFIG.getObsBucketName(), filePath);
        String jobId = null;
        try {
            jobId = submitJobToModertionService(videoUrl,jobMetaInfo, obsFileHandle.generateSharedDownloadUrl());
            if (jobId != null) {
                LOGGER.info(String.format("Create callback task for job[%s].", jobId));
                callbackExecutors.submit(new CallbackTask(videoUrl, jobId, callbackUrl, aisAccessClient));
                return jobId;
            }
        } catch (RejectedExecutionException e1) {
            LOGGER.error(String.format("Submit callback task failed. job_id=%s video_url=%s.", jobId, videoUrl));
        }
        LOGGER.error(String.format("Submit job to moderation service failed for video[%s].", videoUrl));
        return null;
    }

    private String downloadVideo(String videoUrl) throws IOException {
        LOGGER.info("Begin to download video file... url:" + videoUrl);
        try {
            URL url = new URL(videoUrl);
            String urlDecoded = URLDecoder.decode(videoUrl, "UTF-8");
            String fileName = urlDecoded.substring(urlDecoded.lastIndexOf("/") + 1);
            File destFile = new File("data/" + fileName);
            FileUtils.copyURLToFile(url, destFile);
            LOGGER.info("Download done! local:" + destFile.getAbsolutePath());
            return destFile.getAbsolutePath();
        } catch (IOException e) {
            LOGGER.error("Download video failed. video_url:" + videoUrl, e);
            throw e;
        }
    }

    private String submitJobToModertionService(String videoUrl,JobMetaInfo jobMetaInfo, String obsUrl) throws IOException {
        jobMetaInfo.setUrl(obsUrl);

        HttpResponse response = aisAccessClient.post(SUBMIT_JOB_URI, HttpJsonDataUtils.objectToHttpEntity(jobMetaInfo));
        if (!HttpJsonDataUtils.isOKResponded(response)) {
            LOGGER.error(String.format("Submit the job failed, video_url:%s obs_url:%s", videoUrl, obsUrl));
            LOGGER.debug("Request body:" + HttpJsonDataUtils.objectToPrettyJsonString(jobMetaInfo));
            String responseStr = EntityUtils.toString(response.getEntity(), "UTF-8");
            LOGGER.error(responseStr);

        }

        // 获取到提交成功的任务ID, 准备进行结果的查询
        SubmitSuccessRes submitResult = HttpJsonDataUtils.getResponseObject(response, SubmitSuccessRes.class, JSON_ROOT);
        String jobId = submitResult.getJobId();
        LOGGER.info("Submit job done, job_id=" + jobId);
        return jobId;
    }
}
