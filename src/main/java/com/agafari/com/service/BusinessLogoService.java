package com.agafari.com.service;

import com.agafari.com.dto.request.ConfirmImageUploadRequest;
import com.agafari.com.dto.request.PresignLogoRequest;
import com.agafari.com.dto.response.BusinessResponse;
import com.agafari.com.dto.response.PresignLogoResponse;

public interface BusinessLogoService {

    PresignLogoResponse presignLogo(String businessId, PresignLogoRequest req);

    BusinessResponse confirmLogo(String businessId, ConfirmImageUploadRequest req);
}
