package com.agafari.com.admin;

import com.agafari.com.enums.UserRole;
import com.agafari.com.enums.UserStatus;
import com.agafari.com.jpa.entity.User;
import com.agafari.com.jpa.repository.UserRepository;
import com.agafari.com.service.JwtService;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF E4-M1 (AC7, AC8, SC4, SC5): admin APIs need the database-backed ADMIN role on every request; owners and admins are
 * kept apart in both directions; owner and guest permissions are unchanged (owners still reach specials).
 */
class AdminAuthorizationTest extends AbstractIntegrationTest {

    @Autowired private UserRepository users;
    @Autowired private JwtService jwtService;

    @Test
    void ac7AdminApisAre401AnonymousAnd403ForOwnersAndOpenToAdmins() throws Exception {
        Tenant owner = data.tenant("matrix-owner");
        User admin = data.admin("matrix-admin");
        for (String path : new String[]{"/api/admin/me", "/api/admin/audit-events"}) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
            mockMvc.perform(get(path).header("Authorization", data.bearer(owner))).andExpect(status().isForbidden());
            mockMvc.perform(get(path).header("Authorization", data.adminBearer(admin))).andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/admin/me").header("Authorization", data.adminBearer(admin)))
                .andExpect(jsonPath("$.email").value(admin.getEmail()))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void ac8AndSc5AnAdminIsRefusedOnEveryOwnerApiIncludingSpecials() throws Exception {
        Tenant owner = data.tenant("owner-apis");
        String admin = data.adminBearer(data.admin("no-owner-apis"));
        mockMvc.perform(get("/api/menus").header("Authorization", admin)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/specials").header("Authorization", admin)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/category-specials").header("Authorization", admin)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/business/{id}/hours", owner.businessId()).header("Authorization", admin)).andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/user").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"x\"}")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/menus").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"menuTypeId\":\"mt-lunch\"}")).andExpect(status().isForbidden());
    }

    @Test
    void ac8OwnersKeepTheirAccessToSpecialsAndGuestsKeepThePublicMenu() throws Exception {
        Tenant owner = data.tenant("owner-keeps");
        mockMvc.perform(get("/api/specials").header("Authorization", data.bearer(owner))).andExpect(status().isOk());
        mockMvc.perform(get("/api/category-specials").header("Authorization", data.bearer(owner))).andExpect(status().isOk());
        mockMvc.perform(get("/api/menus").header("Authorization", data.bearer(owner))).andExpect(status().isOk());
        mockMvc.perform(get("/venue/{id}/menu", owner.businessId())).andExpect(status().isOk());
    }

    @Test
    void sc4ARoleChangeInTheDatabaseTakesEffectOnTheNextRequest() throws Exception {
        User admin = data.admin("demoted");
        String token = data.adminBearer(admin);
        mockMvc.perform(get("/api/admin/me").header("Authorization", token)).andExpect(status().isOk());

        admin.setRole(UserRole.VENUE);
        users.save(admin);
        mockMvc.perform(get("/api/admin/me").header("Authorization", token)).andExpect(status().isForbidden());
    }

    @Test
    void sc4AnInactiveAdminIsRefusedOnTheNextRequest() throws Exception {
        User admin = data.admin("suspended");
        String token = data.adminBearer(admin);
        admin.setStatus(UserStatus.INACTIVE);
        users.save(admin);
        mockMvc.perform(get("/api/admin/me").header("Authorization", token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_INACTIVE"));
    }

    @Test
    void sc4ARoleClaimInTheTokenGrantsNothing() throws Exception {
        Tenant owner = data.tenant("forged-claim");
        String forged = "Bearer " + jwtService.signToken(owner.owner().getEmail(), owner.owner().getId(), owner.businessId(), "ADMIN");
        mockMvc.perform(get("/api/admin/me").header("Authorization", forged)).andExpect(status().isForbidden());
    }
}
