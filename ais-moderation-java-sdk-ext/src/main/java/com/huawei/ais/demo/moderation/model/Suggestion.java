package com.huawei.ais.demo.moderation.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum Suggestion {

	@JsonProperty("pass")
	PASS,

	@JsonProperty("review")
	REVIEW,

	@JsonProperty("block")
	BLOCK
}
