package com.huawei.ais.demo.moderation.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum JobStatus {

    @JsonProperty("created")
    CREATED,

    @JsonProperty("running")
    RUNNING,

    @JsonProperty("finish")
    FINISH,

    @JsonProperty("failed")
    FAILED
}

