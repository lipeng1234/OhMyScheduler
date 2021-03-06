package com.github.kfcfans.oms.worker.core.executor;

import akka.actor.ActorSelection;
import com.github.kfcfans.oms.common.ExecuteType;
import com.github.kfcfans.oms.worker.OhMyWorker;
import com.github.kfcfans.oms.worker.common.ThreadLocalStore;
import com.github.kfcfans.oms.worker.common.constants.TaskConstant;
import com.github.kfcfans.oms.worker.common.constants.TaskStatus;
import com.github.kfcfans.oms.worker.common.utils.SerializerUtils;
import com.github.kfcfans.oms.worker.core.processor.TaskResult;
import com.github.kfcfans.oms.worker.log.OmsLogger;
import com.github.kfcfans.oms.worker.persistence.TaskDO;
import com.github.kfcfans.oms.worker.persistence.TaskPersistenceService;
import com.github.kfcfans.oms.worker.pojo.model.InstanceInfo;
import com.github.kfcfans.oms.worker.pojo.request.BroadcastTaskPreExecuteFinishedReq;
import com.github.kfcfans.oms.worker.pojo.request.ProcessorReportTaskStatusReq;
import com.github.kfcfans.oms.worker.core.processor.ProcessResult;
import com.github.kfcfans.oms.worker.core.processor.TaskContext;
import com.github.kfcfans.oms.worker.core.processor.sdk.BasicProcessor;
import com.github.kfcfans.oms.worker.core.processor.sdk.BroadcastProcessor;
import com.github.kfcfans.oms.worker.core.processor.sdk.MapReduceProcessor;
import com.google.common.base.Stopwatch;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;

import java.util.List;

/**
 * Processor 执行器
 *
 * @author tjq
 * @since 2020/3/23
 */
@Slf4j
@AllArgsConstructor
public class ProcessorRunnable implements Runnable {


    private final InstanceInfo instanceInfo;
    private final ActorSelection taskTrackerActor;
    private final TaskDO task;
    private final BasicProcessor processor;
    private final OmsLogger omsLogger;

    public void innerRun() throws InterruptedException {

        String taskId = task.getTaskId();
        Long instanceId = task.getInstanceId();

        log.debug("[ProcessorRunnable-{}] start to run task(taskId={}&taskName={})", instanceId, taskId, task.getTaskName());

        // 0. 完成执行上下文准备 & 上报执行信息
        TaskContext taskContext = new TaskContext();
        BeanUtils.copyProperties(task, taskContext);
        taskContext.setJobId(instanceInfo.getJobId());
        taskContext.setMaxRetryTimes(instanceInfo.getTaskRetryNum());
        taskContext.setCurrentRetryTimes(task.getFailedCnt());
        taskContext.setJobParams(instanceInfo.getJobParams());
        taskContext.setInstanceParams(instanceInfo.getInstanceParams());
        taskContext.setOmsLogger(omsLogger);
        if (task.getTaskContent() != null && task.getTaskContent().length > 0) {
            taskContext.setSubTask(SerializerUtils.deSerialized(task.getTaskContent()));
        }
        ThreadLocalStore.setTask(task);

        reportStatus(TaskStatus.WORKER_PROCESSING, null);

        // 1. 根任务特殊处理
        ExecuteType executeType = ExecuteType.valueOf(instanceInfo.getExecuteType());
        if (TaskConstant.ROOT_TASK_NAME.equals(task.getTaskName())) {

            // 广播执行：先选本机执行 preProcess，完成后TaskTracker再为所有Worker生成子Task
            if (executeType == ExecuteType.BROADCAST) {

                BroadcastTaskPreExecuteFinishedReq spReq = new BroadcastTaskPreExecuteFinishedReq();
                spReq.setTaskId(taskId);
                spReq.setInstanceId(instanceId);
                spReq.setSubInstanceId(task.getSubInstanceId());

                if (processor instanceof BroadcastProcessor) {

                    BroadcastProcessor broadcastProcessor = (BroadcastProcessor) processor;
                    try {
                        ProcessResult processResult = broadcastProcessor.preProcess(taskContext);
                        spReq.setSuccess(processResult.isSuccess());
                        spReq.setMsg(suit(processResult.getMsg()));
                    }catch (Exception e) {
                        log.warn("[ProcessorRunnable-{}] broadcast task preProcess failed.", instanceId, e);
                        spReq.setSuccess(false);
                        spReq.setMsg(e.toString());
                    }

                }else {
                    spReq.setSuccess(true);
                    spReq.setMsg("NO_PREPOST_TASK");
                }
                spReq.setReportTime(System.currentTimeMillis());
                taskTrackerActor.tell(spReq, null);

                // 广播执行的第一个 task 只执行 preProcess 部分
                return;
            }
        }

        // 2. 最终任务特殊处理（一定和 TaskTracker 处于相同的机器）
        if (TaskConstant.LAST_TASK_NAME.equals(task.getTaskName())) {

            Stopwatch stopwatch = Stopwatch.createStarted();
            log.debug("[ProcessorRunnable-{}] the last task(taskId={}) start to process.", instanceId, taskId);

            ProcessResult lastResult;
            List<TaskResult> taskResults = TaskPersistenceService.INSTANCE.getAllTaskResult(instanceId, task.getSubInstanceId());
            try {
                switch (executeType) {
                    case BROADCAST:

                        if (processor instanceof  BroadcastProcessor) {
                            BroadcastProcessor broadcastProcessor = (BroadcastProcessor) processor;
                            lastResult = broadcastProcessor.postProcess(taskContext, taskResults);
                        }else {
                            lastResult = BroadcastProcessor.defaultResult(taskResults);
                        }
                        break;
                    case MAP_REDUCE:

                        if (processor instanceof MapReduceProcessor) {
                            MapReduceProcessor mapReduceProcessor = (MapReduceProcessor) processor;
                            lastResult = mapReduceProcessor.reduce(taskContext, taskResults);
                        }else {
                            lastResult = new ProcessResult(false, "not implement the MapReduceProcessor");
                        }
                        break;
                    default:
                        lastResult = new ProcessResult(false, "IMPOSSIBLE OR BUG");
                }
            }catch (Exception e) {
                lastResult = new ProcessResult(false, e.toString());
                log.warn("[ProcessorRunnable-{}] execute last task(taskId={}) failed.", instanceId, taskId, e);
            }

            TaskStatus status = lastResult.isSuccess() ? TaskStatus.WORKER_PROCESS_SUCCESS : TaskStatus.WORKER_PROCESS_FAILED;
            reportStatus(status, suit(lastResult.getMsg()));

            log.info("[ProcessorRunnable-{}] the last task execute successfully, using time: {}", instanceId, stopwatch);
            return;
        }


        // 3. 正式提交运行
        ProcessResult processResult;
        try {
            processResult = processor.process(taskContext);
        }catch (Exception e) {
            log.warn("[ProcessorRunnable-{}] task({}) process failed.", instanceId, taskContext.getDescription(), e);
            processResult = new ProcessResult(false, e.toString());
        }
        reportStatus(processResult.isSuccess() ? TaskStatus.WORKER_PROCESS_SUCCESS : TaskStatus.WORKER_PROCESS_FAILED, suit(processResult.getMsg()));
    }

    /**
     * 上报状态给 TaskTracker
     */
    private void reportStatus(TaskStatus status, String result) {
        ProcessorReportTaskStatusReq req = new ProcessorReportTaskStatusReq();

        req.setInstanceId(task.getInstanceId());
        req.setTaskId(task.getTaskId());
        req.setStatus(status.getValue());
        req.setResult(result);
        req.setReportTime(System.currentTimeMillis());

        taskTrackerActor.tell(req, null);
    }

    @Override
    public void run() {
        try {
            innerRun();
        }catch (InterruptedException ignore) {
        }catch (Exception e) {
            log.error("[ProcessorRunnable-{}] execute failed, please fix this bug @tjq!", task.getInstanceId(), e);
        }finally {
            ThreadLocalStore.clear();
        }
    }

    // 裁剪返回结果到合适的大小
    private String suit(String result) {

        final int maxLength = OhMyWorker.getConfig().getMaxResultLength();
        if (result.length() <= maxLength) {
            return result;
        }
        log.warn("[ProcessorRunnable-{}] task(taskId={})'s result is too large({}>{}), a part will be discarded.",
                task.getInstanceId(), task.getTaskId(), result.length(), maxLength);
        return result.substring(0, maxLength).concat("...");
    }
}
