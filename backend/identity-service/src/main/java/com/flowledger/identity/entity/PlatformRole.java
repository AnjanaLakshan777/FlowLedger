package com.flowledger.identity.entity;

// Platform-wide role, independent of any company membership. SYSTEM_ADMIN is never assigned
// through the API (docs/decisions.md describes the restricted provisioning procedure).
public enum PlatformRole {
    USER,
    SYSTEM_ADMIN
}
