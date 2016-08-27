package org.camunda.bpm.scenario.impl;

import org.camunda.bpm.engine.history.HistoricActivityInstance;
import org.camunda.bpm.engine.impl.calendar.DurationHelper;
import org.camunda.bpm.engine.impl.util.ClockUtil;
import org.camunda.bpm.engine.runtime.MessageCorrelationBuilder;
import org.camunda.bpm.engine.runtime.SignalEventReceivedBuilder;
import org.camunda.bpm.scenario.Scenario;
import org.camunda.bpm.scenario.action.ScenarioAction;

import java.util.Date;
import java.util.Map;

/**
 * @author Martin Schimak <martin.schimak@plexiti.com>
 */
public abstract class ExecutableWaitstate<I> extends AbstractExecutable<I> {

  protected HistoricActivityInstance historicDelegate;
  protected String duration;

  protected ExecutableWaitstate(ProcessRunnerImpl runner, HistoricActivityInstance instance, String duration) {
    super(runner);
    this.historicDelegate = instance;
    this.runtimeDelegate = getRuntimeDelegate();
    this.duration = duration;
  }

  @Override
  public String getExecutionId() {
    return historicDelegate.getExecutionId();
  }

  public String getActivityId() {
    return historicDelegate.getActivityId();
  }

  public void execute() {
    ScenarioAction action = action(runner.scenario);
    if (action == null)
      throw new AssertionError("Process Instance {"
          + getProcessInstance().getProcessDefinitionId() + ", "
          + getProcessInstance().getProcessInstanceId() + "} "
          + "waits at an unexpected " + getClass().getSimpleName().substring(0, getClass().getSimpleName().length() - 9)
          + " '" + historicDelegate.getActivityId() +"'.");
    action.execute(this);
    runner.setExecutedHistoricActivityIds(historicDelegate);
  }

  protected abstract ScenarioAction action(Scenario.Process scenario);

  protected abstract void leave(Map<String, Object> variables);

  public SignalEventReceivedBuilder createSignal(String signalName) {
    return getRuntimeService().createSignalEvent(signalName);
  }

  public MessageCorrelationBuilder createMessage(String messageName) {
    return getRuntimeService().createMessageCorrelation(messageName);
  }

  public Date isExecutableAt() {
    Date endTime = historicDelegate.getStartTime();
    if (duration != null) {
      try {
        if (duration == null || !duration.startsWith("P")) {
          throw new IllegalArgumentException("Provided argument '" + duration + "' is not a duration expression.");
        }
        Date now = ClockUtil.getCurrentTime();
        ClockUtil.setCurrentTime(historicDelegate.getStartTime());
        DurationHelper durationHelper = new DurationHelper(duration);
        endTime = durationHelper.getDateAfter();
        ClockUtil.setCurrentTime(now);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
    return endTime;
  }

}
