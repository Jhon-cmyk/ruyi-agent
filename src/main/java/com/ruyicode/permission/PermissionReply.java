package com.ruyicode.permission;

/**
 * 用户对一次授权请求的答复。feedback 是拒绝时顺带输入的话，
 * 会拼进拒绝结果交给模型，让模型按用户的要求换个做法。
 */
public record PermissionReply(PermissionResponse response, String feedback) {

    public PermissionReply {
        if (feedback == null) feedback = "";
    }

    public static PermissionReply of(PermissionResponse response) {
        return new PermissionReply(response, "");
    }
}
