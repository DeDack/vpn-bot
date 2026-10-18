package com.abcvpn.entity;

import java.time.Instant;
import com.abcvpn.enumeration.DeviceStatus;

public record Device(long id,long userId,String hwid,String deviceModel,String deviceOs,String osVersion,
                     DeviceStatus status,Instant firstSeenAt,Instant lastSeenAt) {}
