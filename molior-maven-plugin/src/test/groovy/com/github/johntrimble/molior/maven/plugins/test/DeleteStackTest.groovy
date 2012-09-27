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
package com.github.johntrimble.molior.maven.plugins.test

import java.util.concurrent.Future
import org.codehaus.plexus.components.interactivity.Prompter;
import org.junit.Assert;
import org.junit.Test;

import com.amazonaws.services.cloudformation.AmazonCloudFormation;
import com.amazonaws.services.cloudformation.AmazonCloudFormationAsync
import com.amazonaws.services.cloudformation.model.DescribeStacksResult;
import com.amazonaws.services.cloudformation.model.ListStacksResult;
import com.amazonaws.services.cloudformation.model.Output;
import com.amazonaws.services.cloudformation.model.Stack;
import com.amazonaws.services.cloudformation.model.StackSummary;
import com.github.johntrimble.molior.maven.plugins.DeleteStackMojo;

class DeleteStackTest {

  @Test
  public void testDeleteWithOutputParameterFilter() {
    Date earlier = new Date(1334009838963)
    Date later = new Date(1334009870979)
    Stack stack1 = new Stack(
      stackName: "test-stack1", 
      stackId: "stack-stack1", 
      creationTime: earlier,
      lastUpdatedTime: earlier, 
      stackStatus: 'CREATE_COMPLETE',
      outputs: [
        new Output(outputKey:'ProvisioningGroup', outputValue:'test-development')
        ],
      parameters: [])
    
    Stack stack2 = new Stack(
      stackName: "test-stack2", 
      stackId: "stack-stack2", 
      creationTime: later,
      lastUpdatedTime: later, 
      stackStatus: 'CREATE_COMPLETE',
      outputs: [
        new Output(outputKey:'ProvisioningGroup', outputValue:'test-development')
        ],
      parameters: [])
    
    List stacks = [stack1, stack2]
    
    AmazonCloudFormationAsync mockClient = [
      'describeStacks': { new DescribeStacksResult(stacks: stacks) },
	  'describeStacksAsync': { def r -> [get: { new DescribeStacksResult(stacks: stacks.findAll({ r.stackName == it.stackId })) }] as Future },
	  'listStacks': { new ListStacksResult().withStackSummaries(new StackSummary().withStackId(stack1.stackId), new StackSummary().withStackId(stack2.stackId)) },
      'deleteStack': { def deleteStackRequest -> stacks = stacks.grep({ !(deleteStackRequest.stackName in [it.stackId, it.stackName]) }) } ] as AmazonCloudFormationAsync
    
    boolean prompterWasCalled = false
    Prompter mockPrompter = [prompt: { s, l, d -> prompterWasCalled = true; "Y"}] as Prompter 
      
    DeleteStackMojo deleteStackMojo = new DeleteStackMojo(
      cloudFormation: mockClient,
      prompter: mockPrompter,
      region: 'us-east-1',
      skipMostRecent: true,
      interactive: true,
      selector: '(outputs.ProvisioningGroup=test-development)')
    
    deleteStackMojo.execute()
    
    Assert.assertTrue( stack2 in stacks )
    Assert.assertFalse( stack1 in stacks )
    Assert.assertTrue( prompterWasCalled )
  }
}
