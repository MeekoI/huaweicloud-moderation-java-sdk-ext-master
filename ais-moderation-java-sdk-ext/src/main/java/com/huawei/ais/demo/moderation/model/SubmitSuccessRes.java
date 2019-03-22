package com.huawei.ais.demo.moderation.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class SubmitSuccessRes {
    @JsonProperty("job_id")
    String jobId;

    public String getJobId() {
        return jobId;
    }
}
