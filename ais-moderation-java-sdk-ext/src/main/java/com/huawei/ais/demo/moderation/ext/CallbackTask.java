package com.huawei.ais.demo.moderation.ext;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.huawei.ais.demo.moderation.model.JobResult;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.entity.ContentType;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.huawei.ais.demo.HttpJsonDataUtils;
import com.huawei.ais.demo.moderation.CommonUtils;
import com.huawei.ais.demo.moderation.Config;
import com.huawei.ais.demo.moderation.model.JobStatus;
import com.huawei.ais.sdk.AisAccess;
import com.huawei.ais.sdk.util.HttpClientUtils;

class CallbackTask implements Runnable {

    private static final Log LOGGER = LogFactory.getLog(CallbackTask.class);

    private static final Config CONFIG = Config.getInstance();

    private static final String GET_JOB_RESULT_URI_TEMPLATE = "/v1.0/moderation/video?job_id=%s";
    private static final String JSON_ROOT = "result";
    private static final long QUERY_JOB_RESULT_INTERVAL = CONFIG.getQueryInterval();

    //Map<任务，还需要重试的次数>
    private static Map<CallbackTask, RetryRecord> callbackFailedTasks = new ConcurrentHashMap<>();
    private static ScheduledExecutorService retryCallbackExecutor;
    private static ExecutorService callbackExecutors;

    private String videoUrl;
    private String jobId;
    private String callbackUrl;
    private AisAccess aisAccessClient;

    CallbackTask(String videoUrl, String jobId, String callbackUrl, AisAccess aisAccessClient) {
        this.videoUrl = videoUrl;
        this.jobId = jobId;
        this.callbackUrl = callbackUrl;
        this.aisAccessClient = aisAccessClient;
    }

    @Override
    public void run() {

        int retryTimes = 0;
        if (callbackFailedTasks.containsKey(this)) {
            int retriedTimes = callbackFailedTasks.get(this).getRetriedTimes();
            retryTimes = retriedTimes + 1;
            LOGGER.info(String.format("Retry[%d/%d] callback for job[%s]", retryTimes, CONFIG.getRetryCallbackTimes(), jobId));
        }
        boolean callbackSuccess = false;
        try {
            Object result = queryJobResult(videoUrl, jobId);
            callbackSuccess = callback(videoUrl, callbackUrl, jobId, result);
        } catch (IOException e) {
            LOGGER.error("Callback error:", e);
        }

        if (callbackSuccess) {
            callbackFailedTasks.remove(this);
        } else {
            if (retryTimes >= CONFIG.getRetryCallbackTimes()) {
                LOGGER.error(String.format("Retry[%d/%d] callback for job[%s], give up!", retryTimes,
                        CONFIG.getRetryCallbackTimes(), jobId));
                callbackFailedTasks.remove(this);
            } else {
                LOGGER.error(String.format("Callback failed for job[%s], will try later.", jobId));
                callbackFailedTasks.put(this, new RetryRecord(retryTimes));
            }
        }

    }

    private Object queryJobResult(String videoUrl, String jobId) throws IOException {

        // 构建进行查询的请求链接，并进行轮询查询，由于是异步任务，必须多次进行轮询
        // 直到结果状态为任务已处理结束
        String url = String.format(GET_JOB_RESULT_URI_TEMPLATE, jobId);
        while (!Thread.currentThread().isInterrupted()) {
            HttpResponse getResponse = aisAccessClient.get(url);
            if (!HttpJsonDataUtils.isOKResponded(getResponse)) {
                LOGGER.error(String.format("Query job[%s] result failed, associated video_url:%s", jobId, videoUrl));
                String responseStr = EntityUtils.toString(getResponse.getEntity(), "UTF-8");
                LOGGER.info(responseStr);
                return responseStr;
            }
            JobResult jobResult
                    = HttpJsonDataUtils.getResponseObject(getResponse, JobResult.class, JSON_ROOT);
            JobStatus jobStatus = jobResult.getStatus();

            // 根据任务状态觉得继续轮询或者打印结果
            if (jobStatus == JobStatus.CREATED || jobStatus == JobStatus.RUNNING) {
                //如果任务还未处理完，等待一段时间，继续进行轮询
                LOGGER.info(String.format("Job[%s] , waiting...", jobId));
                try {
                    Thread.sleep(QUERY_JOB_RESULT_INTERVAL);
                } catch (InterruptedException e) {
                    LOGGER.warn(String.format("Thread[%s] was interrupted, exit.", Thread.currentThread().getName()));
                }
            } else if (jobStatus == JobStatus.FAILED) {
                // 如果处理失败，直接退出
                LOGGER.error(String.format("Job[%s] has failed, associated video_url:%s",
                        jobId, videoUrl));
                return jobResult;
            } else if (jobStatus == JobStatus.FINISH) {
                // 任务处理成功
                LOGGER.info(String.format("Job[%s] has finished.", jobId));
                return jobResult;
            } else {
                LOGGER.info("Should not be here!");
            }
        }
        return null;
    }

    private boolean callback(String videoUrl, String callbackUrl, String jobId, Object result) throws IOException {
        Header[] headers = new Header[]{
                new BasicHeader("Content-Type", ContentType.APPLICATION_JSON.toString())};
        System.out.println(result.toString());
        HttpResponse response = HttpClientUtils.post(callbackUrl, headers,
                HttpJsonDataUtils.objectToHttpEntity(new Notification(jobId, result)), CONFIG.getConnectionTimeout(),
                CONFIG.getConnectionRequestTimeout(), CONFIG.getSocketTimeout());

        if (!HttpJsonDataUtils.isOKResponded(response)) {
            LOGGER.error(String.format("Callback for job[%s] failed, associated video_url:%s", jobId, videoUrl));
            LOGGER.debug("Request body:" + HttpJsonDataUtils.objectToJsonString(result));
            LOGGER.error(EntityUtils.toString(response.getEntity(), "UTF-8"));
            return false;
        } else {
            LOGGER.info(String.format("Callback for job[%s] done.", jobId));
            return true;
        }
    }

    public String getJobId() {
        return this.jobId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(jobId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        CallbackTask that = (CallbackTask) o;
        return Objects.equals(jobId, that.jobId);
    }

    protected static void initCallbackFailedTaskManager(ExecutorService callbackExecutors) {
        CallbackTask.callbackExecutors = callbackExecutors;
        retryCallbackExecutor = Executors.newSingleThreadScheduledExecutor(
                CommonUtils.ThreadFactoryConstructor(true, "moderation-sdk-retry-callback-%d"));

        retryCallbackExecutor.scheduleAtFixedRate(
                new FailedCallbackTasksScanner(),
                CONFIG.getRetryCallbackInterval(),
                CONFIG.getRetryCallbackInterval() / 3,
                TimeUnit.SECONDS);
    }

    protected static void destroyCallbackFailedTaskManager() {
        CommonUtils.destroyExecutors(retryCallbackExecutor, "retryCallbackExecutor");
        callbackFailedTasks.clear();
    }

    static class FailedCallbackTasksScanner implements Runnable {
        @Override
        public void run() {
            long nowInSeconds;
            for (Map.Entry<CallbackTask, RetryRecord> entry : callbackFailedTasks.entrySet()) {
                nowInSeconds = TimeUnit.SECONDS.convert(System.nanoTime(), TimeUnit.NANOSECONDS);
                //只有没有处于没有正在重试且距上次重试间隔等于或超过设定门限状态的回调task才会被再次提交到回调任务池中
                if (!entry.getValue().isRetrying()
                        && nowInSeconds - entry.getValue().getLatestRetryTime() >= CONFIG.getRetryCallbackInterval()) {
                    try {
                        LOGGER.info(String.format("Submit retry callback task for job[%s].", entry.getKey().getJobId()));
                        callbackExecutors.submit(entry.getKey());
                        entry.getValue().setRetrying(true);
                    } catch (RejectedExecutionException e) {
                        LOGGER.error(String.format("Failed to submit retry callback task for job[%s] failed, try later.",
                                entry.getKey().getJobId()), e);
                    }
                }
            }
        }
    }


    static class RetryRecord {
        private int retriedTimes;
        private long latestRetryTime;
        private boolean isRetrying;

        RetryRecord(int retriedTimes) {
            this.retriedTimes = retriedTimes;
            this.latestRetryTime = TimeUnit.SECONDS.convert(System.nanoTime(), TimeUnit.NANOSECONDS);
            this.isRetrying = false;
        }

        int getRetriedTimes() {
            return retriedTimes;
        }

        long getLatestRetryTime() {
            return latestRetryTime;
        }

        boolean isRetrying() {
            return isRetrying;
        }

        void setRetrying(boolean retrying) {
            isRetrying = retrying;
        }
    }

    static class Notification {
        @JsonProperty("job_id")
        String jobId;
        @JsonProperty("moderation_result")
        Object resultFromEngine;

        public Notification(String jobId, Object resultFromEngine) {
            this.jobId = jobId;
            this.resultFromEngine = resultFromEngine;
        }
    }
}
