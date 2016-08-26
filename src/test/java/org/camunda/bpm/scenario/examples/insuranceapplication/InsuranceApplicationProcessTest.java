package org.camunda.bpm.scenario.examples.insuranceapplication;

import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.test.Deployment;
import org.camunda.bpm.engine.test.ProcessEngineRule;
import org.camunda.bpm.engine.variable.Variables;
import org.camunda.bpm.scenario.Scenario;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Map;

import static org.camunda.bpm.engine.test.assertions.ProcessEngineTests.assertThat;
import static org.camunda.bpm.engine.test.assertions.ProcessEngineTests.runtimeService;
import static org.camunda.bpm.engine.test.assertions.ProcessEngineTests.withVariables;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Martin Schimak <martin.schimak@plexiti.com>
 */
@Deployment(resources = {"InsuranceApplication.bpmn", "DocumentRequest.bpmn", "RiskCheck.dmn"})
public class InsuranceApplicationProcessTest {

  @Rule public ProcessEngineRule rule = new ProcessEngineRule();

  // Mock all waitstates in main process and call activity with a scenario
  @Mock private Scenario.Process insuranceApplication;
  @Mock private Scenario.Process documentRequest;
  private Map<String, Object> variables;

  // Setup a default behaviour for all "completable" waitstates in your
  // processes. You might want to override the behaviour in test methods.
  @Before
  public void setupDefaultScenario() {

    MockitoAnnotations.initMocks(this);

    variables = Variables.createVariables()
      .putValue("applicantAge", "30")
      .putValue("carManufacturer", "VW")
      .putValue("carType", "Golf");

    when(insuranceApplication.actsOnUserTask("UserTaskDecideAboutApplication")).thenReturn((task) -> {
      task.complete(withVariables("approved", true));
    });

    when(insuranceApplication.actsOnUserTask("UserTaskCheckApplicationUnderwriter")).thenReturn((task) -> {
      assertThat(task).hasCandidateGroup("underwriter");
      task.complete();
    });

    when(insuranceApplication.actsOnUserTask("UserTaskCheckApplicationTeamlead")).thenReturn((task) -> {
      assertThat(task).hasCandidateGroup("teamlead");
      task.complete();
    });

    when(insuranceApplication.actsOnUserTask("UserTaskSpeedUpManualCheck")).thenReturn((task) -> {
      assertThat(task).hasCandidateGroup("management");
      task.complete();
    });

    when(insuranceApplication.actsOnSendTask("SendTaskSendPolicy")).thenReturn((externalTask) -> {
      assertThat(externalTask.getTopicName()).isEqualTo("SendMail");
      externalTask.complete();
    });

    when(insuranceApplication.actsOnSendTask("SendTaskSendRejection")).thenReturn((externalTask) -> {
      assertThat(externalTask.getTopicName()).isEqualTo("SendMail");
      externalTask.complete();
    });

    when(insuranceApplication.runsCallActivity("CallActivityDocumentRequest"))
      .thenReturn(Scenario.use(documentRequest));

    when(documentRequest.actsOnSendTask("SendTaskRequestDocuments")).thenReturn((externalTask) -> {
      assertThat(externalTask.getTopicName()).isEqualTo("SendMail");
      externalTask.complete();
    });

    when(documentRequest.actsOnReceiveTask("ReceiveTaskWaitForDocuments")).thenReturn((receiveTask) -> {
      assertThat(receiveTask.getEventType()).isEqualTo("message");
      assertThat(receiveTask.getEventName()).isEqualTo("MSG_DOCUMENT_RECEIVED");
      receiveTask.receiveMessage();
    });

    when(documentRequest.actsOnUserTask("UserTaskCallCustomer")).thenReturn((task) -> {
      task.complete();
    });

    when(documentRequest.actsOnSendTask("SendTaskSendReminder")).thenReturn((externalTask) -> {
      assertThat(externalTask.getTopicName()).isEqualTo("SendMail");
      externalTask.complete();
    });

  }

  @Test
  public void testGreenScenario() {

    // when

    ProcessInstance pi = Scenario.run(insuranceApplication)
        .startBy("InsuranceApplication", variables) // either just start process by key ...
        .execute();

    // then

    assertThat(pi).variables().containsEntry("riskAssessment", "green");
    verify(insuranceApplication, never()).hasStarted("SubProcessManualCheck");
    verify(insuranceApplication).hasFinished("EndEventApplicationAccepted");

  }

  @Test
  public void testYellowScenario() {

    // given
    variables = Variables.createVariables()
      .putValue("applicantAge", 30)
      .putValue("carManufacturer", "Porsche")
      .putValue("carType", "911");

    // when

    ProcessInstance pi = Scenario.run(insuranceApplication)
      .startBy(() -> { // ... or define your own starter function
        return rule.getRuntimeService().startProcessInstanceByKey("InsuranceApplication", variables);
      })
      .execute();

    // then

    assertThat(pi).variables().containsEntry("riskAssessment", "yellow");
    verify(insuranceApplication).hasCompleted("SubProcessManualCheck");
    verify(insuranceApplication).hasFinished("EndEventApplicationAccepted");

  }

  @Test
  public void testRedScenario() {

    // given

    variables = Variables.createVariables()
      .putValue("applicantAge", 20)
      .putValue("carManufacturer", "Porsche")
      .putValue("carType", "911");

    // when

    ProcessInstance pi = Scenario.run(insuranceApplication)
        .startBy("InsuranceApplication", variables)
        .execute();

    // then

    assertThat(pi).variables().containsEntry("riskAssessment", "red");

    verify(insuranceApplication, never()).hasStarted("SubProcessManualCheck");
    verify(insuranceApplication).hasFinished("EndEventApplicationRejected");

  }

  @Test
  public void testManualApprovalScenario() {

    // given

    variables = Variables.createVariables()
      .putValue("applicantAge", 30)
      .putValue("carManufacturer", "Porsche")
      .putValue("carType", "911");

    // when

    ProcessInstance pi = Scenario.run(insuranceApplication)
        .startBy("InsuranceApplication", variables)
        .execute();

    // then

    assertThat(pi).variables()
      .containsEntry("riskAssessment", "yellow")
      .containsEntry("approved", true);

    verify(insuranceApplication).hasCompleted("SubProcessManualCheck");
    verify(insuranceApplication).hasFinished("EndEventApplicationAccepted");

  }

  @Test
  public void testManualRejectionScenario() {

    // given

    variables = Variables.createVariables()
      .putValue("applicantAge", 30)
      .putValue("carManufacturer", "Porsche")
      .putValue("carType", "911");

    when(insuranceApplication.actsOnUserTask("UserTaskDecideAboutApplication")).thenReturn((task) -> {
      task.complete(withVariables("approved", false));
    });

    // when

    ProcessInstance pi = Scenario.run(insuranceApplication)
        .startBy("InsuranceApplication", variables)
        .execute();

    // then

    assertThat(pi).variables()
      .containsEntry("riskAssessment", "yellow")
      .containsEntry("approved", false);

    verify(insuranceApplication).hasCompleted("SubProcessManualCheck");
    verify(insuranceApplication).hasFinished("EndEventApplicationRejected");

  }

  @Test
  public void testDocumentRequestScenario() {

    // given

    variables = Variables.createVariables()
        .putValue("applicantAge", 30)
        .putValue("carManufacturer", "Porsche")
        .putValue("carType", "911");

    when(insuranceApplication.actsOnUserTask("UserTaskDecideAboutApplication")).thenReturn((task) -> {
      runtimeService().correlateMessage("msgDocumentNecessary");
      task.complete(withVariables("approved", true));
    });

    // when

    Scenario.run(insuranceApplication)
        .startBy("InsuranceApplication", variables)
        .execute();

    // then

    verify(insuranceApplication).hasCompleted("CallActivityDocumentRequest");

  }

  @Test
  public void testDocumentRequestBitLateScenario() {

    // given

    variables = Variables.createVariables()
      .putValue("applicantAge", 30)
      .putValue("carManufacturer", "Porsche")
      .putValue("carType", "911");

    when(insuranceApplication.actsOnUserTask("UserTaskDecideAboutApplication")).thenReturn((task) -> {
      runtimeService().correlateMessage("msgDocumentNecessary");
      task.complete(withVariables("approved", true));
    });

    when(documentRequest.waitsForActionOn("ReceiveTaskWaitForDocuments")).thenReturn("P1D");

    when(documentRequest.actsOnReceiveTask("ReceiveTaskWaitForDocuments")).thenReturn((receiveTask) -> {
      receiveTask.receiveMessage();
    });

    // when

    Scenario.run(insuranceApplication)
        .startBy("InsuranceApplication", variables)
        .execute();

    // then

    verify(insuranceApplication).hasCompleted("CallActivityDocumentRequest");
    verify(insuranceApplication, never()).hasStarted("UserTaskSpeedUpManualCheck");
    verify(documentRequest).hasCompleted("SendTaskSendReminder");

    // and you could principally also ...

    verify(documentRequest, times(1)).waitsForActionOn("ReceiveTaskWaitForDocuments");
    verify(documentRequest, times(1)).actsOnReceiveTask("ReceiveTaskWaitForDocuments");
    verify(documentRequest, never()).waitsForActionOn("UserTaskCallCustomer");

  }

  @Test
  public void testDocumentRequestVeryLateScenario() {

    // given

    variables = Variables.createVariables()
        .putValue("applicantAge", 30)
        .putValue("carManufacturer", "Porsche")
        .putValue("carType", "911");

    when(insuranceApplication.actsOnUserTask("UserTaskDecideAboutApplication")).thenReturn((task) -> {
      runtimeService().correlateMessage("msgDocumentNecessary");
      task.complete(withVariables("approved", true));
    });

    when(documentRequest.waitsForActionOn("ReceiveTaskWaitForDocuments")).thenReturn("P7D");

    // when

    Scenario.run(insuranceApplication)
        .startBy("InsuranceApplication", variables)
        .execute();

    // then

    verify(insuranceApplication, times(1)).hasStarted("UserTaskSpeedUpManualCheck");
    verify(insuranceApplication).hasCompleted("EndEventApplicationAccepted");

    verify(documentRequest, times(1)).hasCompleted("UserTaskCallCustomer");
    verify(documentRequest, times(5)).hasCompleted("SendTaskSendReminder");
    verify(documentRequest).hasCanceled("ReceiveTaskWaitForDocuments");
    verify(documentRequest, never()).hasCompleted("ReceiveTaskWaitForDocuments");

  }

  @Test
  public void testParsingAndDeployment() {
    // nothing is done here, as we just want to check for exceptions during deployment
  }

}
