package com.agafari.com.dto.request;

/** AGF E4-M7: pause, resume, archive or remove the image of an ad (optimistic: the version the admin saw). */
public record AdActionRequest(Integer rowVersion) {
}
