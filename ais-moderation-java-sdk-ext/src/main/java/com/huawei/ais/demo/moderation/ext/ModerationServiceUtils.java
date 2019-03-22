package com.huawei.ais.demo.moderation.ext;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.huawei.ais.demo.moderation.model.JobMetaInfo;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.huawei.ais.common.AuthInfo;
import com.huawei.ais.common.ProxyHostInfo;
import com.huawei.ais.demo.moderation.CommonUtils;
import com.huawei.ais.demo.moderation.Config;
import com.huawei.ais.demo.obs.SimpleObsClient;
import com.huawei.ais.sdk.AisAccess;

/**
 * 视频审核服务调用工具类，管理两个线程池<p/>
 * - submitJobExecutors：来执行“下载视频-上传OBS-提交OBS地址给云端引擎”任务<br/>
 * - callbackExecutors：用来执行查询“任务结果-回调”任务<p/>
 * 如果在产品中使用AsrServiceUtils，注意在合适的位置调用destroy()方法来销毁线程池
 */
public class ModerationServiceUtils {

    private static final Log LOGGER = LogFactory.getLog(ModerationServiceUtils.class);

    private static final Config CONFIG = Config.getInstance();

    private AisAccess aisAccessClient;
    private SimpleObsClient simpleObsClient;

    private ExecutorService submitJobExecutors = null;
    private ExecutorService callbackExecutors = null;

    private ModerationServiceUtils() {
        init();
    }

    /**
     * 调用视频审核服务
     *
     * @param videoUrl    视频审核的文件的url
     * @param callbackUrl 审核结束后的回调url
     * @param jobMetaInfo 视频审核相关参数
     * @return 视频提交到视频审核引起的任务句柄，任务提交成功后可通过句柄取到云端识别任务的jobId
     */
    public Future<String> callAsrService(String videoUrl, String callbackUrl, JobMetaInfo jobMetaInfo) {
        return submitJobExecutors.submit(
                new SubmitJobTask(videoUrl,jobMetaInfo, callbackUrl, aisAccessClient, simpleObsClient, callbackExecutors));
    }

    /**
     * 销毁ModerationServiceUtils控制的资源
     */
    public void destroy() {
        CallbackTask.destroyCallbackFailedTaskManager();
        CommonUtils.destroyExecutors(submitJobExecutors, "submitJobExecutors");
        CommonUtils.destroyExecutors(callbackExecutors, "callbackExecutors");
    }

    private void init() {

        AuthInfo authInfo = new AuthInfo(CONFIG.getModerationEndpoint(), CONFIG.getModerationRegion(), CONFIG.getAk(), CONFIG.getSk());
        ProxyHostInfo proxyHostInfo = new ProxyHostInfo("proxycn2.xxx.com", 8080, "", "");

        aisAccessClient = new AisAccess(authInfo, CONFIG.getConnectionTimeout(), CONFIG.getConnectionRequestTimeout(),
                CONFIG.getSocketTimeout());
        simpleObsClient = new SimpleObsClient(authInfo);

        //aisAccessClient = new AisAccessWithProxy(asrAuthInfo, proxyHostInfo, CONFIG.getConnectionTimeout(),
        //        CONFIG.getConnectionRequestTimeout(), CONFIG.getSocketTimeout());
        //simpleObsClient = new SimpleObsClient(asrAuthInfo, proxyHostInfo);


        //初始submitJobExecutors
        submitJobExecutors = new ThreadPoolExecutor(
                CONFIG.getSubmitPoolCoreSize(),
                CONFIG.getSubmitPoolMaxSize(),
                CONFIG.getSubmitPoolKeepAliveTime(),
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(CONFIG.getSubmitPoolQueueSize()),
                CommonUtils.ThreadFactoryConstructor(true, "moderation-sdk-submit-job-%d"),
                new ThreadPoolExecutor.AbortPolicy());

        //初始callbackExecutors
        callbackExecutors = new ThreadPoolExecutor(
                CONFIG.getCallbackPoolCoreSize(),
                CONFIG.getCallbackPoolMaxSize(),
                CONFIG.getCallbackPoolKeepAliveTime(),
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(CONFIG.getCallbackPoolQueueSize()),
                CommonUtils.ThreadFactoryConstructor(true, "moderation-sdk-callback-%d"),
                new ThreadPoolExecutor.AbortPolicy());

        CallbackTask.initCallbackFailedTaskManager(callbackExecutors);
        //创建obs桶
        simpleObsClient.createBucket(CONFIG.getObsBucketName());

        LOGGER.info("ModerationServiceUtils init successfully.");
    }

    /**
     * 获取ModerationServiceUtils实例（单例）
     *
     * @return ModerationServiceUtils实例
     */
    public static ModerationServiceUtils getInstance() {
        return SingletonConstructor.moderationServiceUtils;
    }

    static class SingletonConstructor {
        static ModerationServiceUtils moderationServiceUtils = new ModerationServiceUtils();
    }

}
