package com.huawei.ais.demo.moderation.ext;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.huawei.ais.demo.moderation.model.Category;
import com.huawei.ais.demo.moderation.model.JobMetaInfo;
import org.junit.Test;

public class ModerationServiceUtilsTest {

    @Test
    public void test() throws ExecutionException, InterruptedException {
        ModerationServiceUtils moderationServiceUtils = ModerationServiceUtils.getInstance();
        JobMetaInfo jobMetaInfo = new JobMetaInfo();
        jobMetaInfo.setFrameInterval(5);
        jobMetaInfo.addCategory(Category.POLITICS);
        jobMetaInfo.addCategory(Category.TERRORISM);
        jobMetaInfo.addCategory(Category.PORN);

        Future<String> future = moderationServiceUtils.callAsrService("https://obs-test-llg.obs.cn-north-1.myhuaweicloud.com/bgm_recognition",
                "http://127.0.0.1:8080/result/notification",jobMetaInfo);

        Future<String> future1 = moderationServiceUtils.callAsrService("https://obs-test-llg.obs.cn-north-1.myhuaweicloud.com/bgm_recognition",
                "http://127.0.0.1:8080/result/notification",jobMetaInfo);

        //获取JobId的动作是阻塞性的，在视频提交到云端的视频审核前不会返回
        //System.out.println("-------------jobId1=" + future1.get());

        //模拟进程运行一段时间后被停止，释放资源
        Thread.sleep(TimeUnit.MILLISECONDS.convert(2, TimeUnit.MINUTES));
        moderationServiceUtils.destroy();
    }
}
