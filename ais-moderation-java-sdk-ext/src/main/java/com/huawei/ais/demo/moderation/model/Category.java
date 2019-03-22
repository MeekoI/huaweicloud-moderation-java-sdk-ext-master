package com.huawei.ais.demo.moderation.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum Category {
    @JsonProperty("politics")
    POLITICS,

    @JsonProperty("terrorism")
    TERRORISM,

    @JsonProperty("porn")
    PORN
}
