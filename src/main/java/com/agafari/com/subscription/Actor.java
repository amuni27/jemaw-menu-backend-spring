package com.agafari.com.subscription;

/** AGF SUB-R2-02: who performs a lifecycle command (the user id recorded on the subscription row). */
public record Actor(String userId) {

    public Actor {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("An actor is required");
    }
}
