// Copyright 2018 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.uicd.backend.core.uicdactions;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.uicd.backend.core.devicesdriver.AndroidDeviceDriver;
import com.google.uicd.backend.core.exceptions.UicdExternalCommandException;
import com.google.uicd.backend.core.globalvariables.UicdGlobalVariableMap;
import com.google.uicd.backend.core.utils.ADBCommandLineUtil;
import com.google.uicd.backend.core.utils.CommandLineUtil;
import java.util.ArrayList;
import java.util.List;

/** CommandLineAction For command or adb command input */
public class CommandLineAction extends BaseAction implements IValidatorAction {

  public String commandLine;
  public boolean isAdbCommand;
  public boolean logOutput;
  public String expectedReturnCode;
  public Integer commandlineExecutionTimeoutSec;
  public boolean needShellOutput;
  private String outputPath;
  @JsonIgnore private String exitValue;

  public CommandLineAction() {}

  public CommandLineAction(
      boolean isAdbCommand,
      String commandLine,
      String expectedReturnCode,
      Integer commandlineExecutionTimeoutSec,
      boolean needShellOutput) {
    this.isAdbCommand = isAdbCommand;
    this.commandLine = commandLine;
    this.expectedReturnCode = expectedReturnCode;
    this.commandlineExecutionTimeoutSec = commandlineExecutionTimeoutSec;
    this.needShellOutput = needShellOutput;
    setName(commandLine);
  }

  @Override
  public String getDisplay() {
    return commandLine == null ? "None" : this.commandLine;
  }

  @Override
  public void updateAction(BaseAction baseAction) {
    super.updateBaseAction(baseAction);
    CommandLineAction commandLineAction = (CommandLineAction) baseAction;
    this.commandLine = commandLineAction.commandLine;
    this.expectedReturnCode = commandLineAction.expectedReturnCode;
    this.commandlineExecutionTimeoutSec = commandLineAction.commandlineExecutionTimeoutSec;
    this.needShellOutput = commandLineAction.needShellOutput;
  }

  @Override
  protected int play(AndroidDeviceDriver androidDeviceDriver, ActionContext actionContext)
      throws UicdExternalCommandException {
    List<String> output = new ArrayList<>();
    String deviceId = androidDeviceDriver.getDeviceId();
    String commandLine = actionContext.expandUicdGlobalVariable(this.commandLine, deviceId);

    if (isAdbCommand) {
      ADBCommandLineUtil.executeAdb(commandLine, deviceId, output, commandlineExecutionTimeoutSec);
    } else {
      Process process =
          CommandLineUtil.execute(commandLine, output, true, commandlineExecutionTimeoutSec);
      exitValue = String.valueOf(process.exitValue());
      boolean isValid = validate(actionContext, androidDeviceDriver);
      if (!isValid) {
        actionContext.setFailStatus(deviceId);
        this.playStatus = ActionContext.PlayStatus.FAIL;
      }
    }

    // Process output from command line. We are looking for a str in the standard output like this:
    // 'uicd_shell_output:{"$uicd_var1": {"value": "app uicd", "exportFiled": false}}"
    StringBuilder sb = new StringBuilder();
    for (String s : output) {
      logger.info(s);
      if (needShellOutput && s.contains(UicdGlobalVariableMap.UICD_SHELL_OUTPUT_KEYWORD)) {
        String jsonContent = s.replace(UicdGlobalVariableMap.UICD_SHELL_OUTPUT_KEYWORD, "");
        // only allow keys that start with "$uicd" in the map.
        if (jsonContent.contains(UicdGlobalVariableMap.UICD_PARAM_KEYWORD)) {
          sb.append(jsonContent);
        } else {
          logger.warning(
              String.format("Keys should start with $uicd, instead found %s", jsonContent));
        }
      }
    }
    if (sb.length() > 0) {
      actionContext.getGlobalVariableMap().fillRawMapByJson(sb.toString());
    }
    return 0;
  }

  @Override
  protected ActionExecutionResult genActionExecutionResults(
      AndroidDeviceDriver androidDeviceDriver, ActionContext actionContext) {
    ActionExecutionResult actionExecutionResult = new ActionExecutionResult();
    String displayWithParameters =
        actionContext.expandUicdGlobalVariable(this.commandLine, androidDeviceDriver.getDeviceId());
    if (logOutput) {
      actionExecutionResult.setLogOutput(outputPath, displayWithParameters);
    } else {
      actionExecutionResult.setRegularOutput(displayWithParameters);
    }
    actionExecutionResult.setActionId(this.getActionId().toString());
    actionExecutionResult.setPlayStatus(this.playStatus);
    return actionExecutionResult;
  }

  @Override
  public boolean validate(ActionContext actionContext, AndroidDeviceDriver androidDeviceDriver) {
    return expectedReturnCode == null
        || expectedReturnCode.length() == 0
        || this.exitValue.equals(expectedReturnCode);
  }
}
