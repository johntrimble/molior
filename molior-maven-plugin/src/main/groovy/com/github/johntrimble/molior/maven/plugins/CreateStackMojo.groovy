/**
 * Copyright (C) 2012 John Trimble <trimblej@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.johntrimble.molior.maven.plugins

import java.io.File
import java.util.Map

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugin.MojoFailureException
import org.codehaus.gmaven.common.ArtifactItem
import org.joda.time.Hours
import org.joda.time.Interval
import org.joda.time.PeriodType
import org.joda.time.format.PeriodFormat

import com.amazonaws.services.cloudformation.model.Stack
import com.amazonaws.services.cloudformation.model.CreateStackRequest
import com.amazonaws.services.cloudformation.model.DescribeStacksRequest
import com.amazonaws.services.cloudformation.model.Parameter
import com.amazonaws.services.cloudformation.model.StackStatus

/**
*
* @author John Trimble <john.trimble@meltmedia.com>
*
* @aggregator
* @goal createStack
*/
class CreateStackMojo extends AbstractMojo {

  /**
   * @parameter default-value="stack"
   */
  String stackPropertyName
  
  /**
   * @parameter
   */
  String stackName
 
  /**
   * @parameter
   */
  String stackNamePrefix
  
  /**
   * Additional input parameters for the CloudFormation template.
   *
   * @parameter
   */
  Map parameters
  
  /**
   * The CloudFormation template file.
   *
   * @parameter expression="${project.build.outputDirectory}/provision.template"
   */
  File template
    
  /**
   * The Maven artifact for the CloudFormation template.
   * @parameter
   */
  ArtifactItem templateArtifact
  
  /**
   * Timeout in minutes.
   * 
   * @parameter default-value="30"
   */
  int cloudFormationTimeout
  
  void execute() throws MojoExecutionException, MojoFailureException {
    if( !stackName ) 
      stackName = "${stackNamePrefix}${new Date().format('yyyyMMddHHmmss')}"
    
    template = findTemplate()

    log.info "Using template: ${template.absolutePath}"
    this.parameters.each { key, value -> log.info "\t${key} = ${value}" }

    // Create stack parameters    
    List cfParameters = []
    this.parameters.collect cfParameters, { key, value -> new Parameter(parameterKey:key, parameterValue: value) }

    // Create stack
    String stackId = cloudFormation.createStack(new CreateStackRequest(stackName:stackName, templateBody:template.text, timeoutInMinutes: cloudFormationTimeout, parameters: cfParameters)).stackId
    log.info "Creating stack: ${stackId}"
    
    // Poll stack until created
    Date startTime = new Date()
    log.info "Waiting for stack to start..."

    Stack stack = null
    for( ;; ) {
      stack = cloudFormation.describeStacks(new DescribeStacksRequest(stackName: stackId)).stacks.find { it }
      if( StackStatus.valueOf(stack.stackStatus) == StackStatus.CREATE_COMPLETE ) {
        break
      } else if( StackStatus.valueOf(stack.stackStatus) != StackStatus.CREATE_IN_PROGRESS ) {
        fail("Failed to create stack ${stackId}")
      }
      Thread.currentThread().sleep 60*1000
    }
    
    Date endTime = new Date()
    
    String elapsed = (PeriodFormat.wordBased().print( 
      new Interval(startTime.time, endTime.time)
      .toPeriod(PeriodType.time().withSecondsRemoved().withMillisRemoved())))
    
    
    log.info "Stack '${stackId}' created in ${elapsed}."
    stack.outputs.each { log.info "\t${it.outputKey} = ${it.outputValue}" }
  }
  
  def findTemplate() {
    // Find template
    if( templateArtifact ) {
      Artifact artifact = getArtifact(templateArtifact)
      template = artifact.file
    }
    
    if( !template ) {
      fail("No template specified.")
    }
    
    if( template && !template.exists() ) {
      fail("Template file '${template.path}' does not exist.")
    }
    return template
  }

}
