package com.github.kfcfans.oms.common.request;

import com.github.kfcfans.oms.common.OmsSerializable;
import com.github.kfcfans.oms.common.model.SystemMetrics;
import lombok.Data;


/**
 * Worker 上报健康信息（worker定时发送的heartbeat）
 *
 * @author tjq
 * @since 2020/3/25
 */
@Data
public class WorkerHeartbeat implements OmsSerializable {

    // 本机地址 -> IP:port
    private String workerAddress;
    // 当前 appName
    private String appName;
    // 当前 appId
    private Long appId;
    // 当前时间
    private long heartbeatTime;

    private SystemMetrics systemMetrics;
}
