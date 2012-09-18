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
import java.util.List
import java.util.Map
import java.util.logging.LogManager

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory
import org.apache.maven.artifact.Artifact
import org.apache.maven.artifact.factory.ArtifactFactory
import org.apache.maven.artifact.metadata.ArtifactMetadataSource
import org.apache.maven.artifact.repository.ArtifactRepository
import org.apache.maven.artifact.resolver.ArtifactResolver
import org.apache.maven.artifact.versioning.VersionRange
import org.apache.maven.project.MavenProject
import org.codehaus.gmaven.common.ArtifactItem
import org.codehaus.gmaven.mojo.GroovyMojo
import org.codehaus.plexus.components.interactivity.Prompter

import org.jfrog.maven.annomojo.annotations.MojoParameter
import org.jfrog.maven.annomojo.annotations.MojoComponent

import com.amazonaws.auth.AWSCredentials
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.PropertiesCredentials
import com.amazonaws.http.AmazonHttpClient
import com.amazonaws.services.cloudformation.AmazonCloudFormation;
import com.amazonaws.services.cloudformation.AmazonCloudFormationAsync
import com.amazonaws.services.cloudformation.AmazonCloudFormationAsyncClient;
import com.amazonaws.services.cloudformation.AmazonCloudFormationClient
import com.amazonaws.services.ec2.AmazonEC2
import com.amazonaws.services.ec2.AmazonEC2Client
import com.amazonaws.services.cloudformation.model.Stack

abstract class AbstractMojo extends GroovyMojo {
  static {
    // The AWS bindings tend to do a lot of logging... disable it.
    LogManager.getLogManager().reset()
  }
  
  @MojoParameter(expression='${project}')
  public MavenProject project;

  @MojoComponent
  public ArtifactFactory artifactFactory

  @MojoComponent
  public ArtifactResolver artifactResolver

  @MojoComponent
  public ArtifactMetadataSource artifactMetadataSource

  @MojoParameter(defaultValue='${localRepository}')
  public ArtifactRepository artifactRepository

  @MojoParameter(expression='${project.pluginArtifactRepositories')
  List remoteRepositories

  @MojoComponent
  public Prompter prompter

  void setPrompter( Prompter prompter ) {
    this.prompter = prompter;
  }

  @MojoParameter(defaultValue='true', expression='${interactive}')
  public boolean interactive
  
  @MojoParameter(expression='${aws.accessKey}')
  public String accessKey
  
  @MojoParameter(expression='${aws.secretKey}')
  public String secretKey
  
  /**
   * File containing AWS credentials in the following format:
   * accessKey=YOUR_ACCESS_KEY
   * secretKey=YOUR_SECRET_KEY
   */
  @MojoParameter(expression='${user.home}/.aws_credentials')
  public File credentialsFile

  /**
   * The AWS region to use.
   */
  @MojoParameter(defaultValue='us-west-1')
  public String region

  // Mapping of regions to AWS endpoints.
  private Map regionEndpoints = [
    'us-west-1': [
      'cloudformation': 'cloudformation.us-west-1.amazonaws.com',
      'ec2': 'ec2.us-west-1.amazonaws.com'],
    'us-west-2': [
      'cloudformation': 'cloudformation.us-west-2.amazonaws.com',
      'ec2': 'ec2.us-west-2.amazonaws.com'],
    'us-east-1': [
      'cloudformation': 'cloudformation.us-east-1.amazonaws.com',
      'ec2': 'ec2.us-east-1.amazonaws.com']]

  /**
   * Creates a CloudFormation client using the given credentials and endpoint.
   *
   * @param credentials
   * @param endpoint
   * @return
   */
  AmazonCloudFormation createCloudFormation(AWSCredentials credentials, String endpoint) {
    AmazonCloudFormationAsyncClient cf = new AmazonCloudFormationAsyncClient(credentials)
    cf.endpoint = endpoint
    return cf
  }
  
  /**
   * Creates an EC2 client using the given credentials and endpoint.
   *
   * @param credentials
   * @param endpoint
   * @return
   */
  AmazonEC2 createEC2(AWSCredentials credentials, String endpoint) {
    AmazonEC2Client ec2 = new AmazonEC2Client(credentials)
    ec2.endpoint = endpoint
    return ec2
  }

  public AWSCredentials getCredentials() {
    AWSCredentials credentials = null
    
    if( this.accessKey && this.secretKey ) {
      credentials = new BasicAWSCredentials(this.accessKey, this.secretKey)
    } else if( !credentialsFile ) {
      fail("Must specify either credentialsFile or both accessKey and secretKey.")
    } else if( !credentialsFile.exists() ) {
      fail("Specified credentials file ${credentialsFile.absolutePath} does not exist.")
    } else { 
      credentials = new PropertiesCredentials(credentialsFile)
    }
    
    return credentials
  }

  /**
   * EC2 instance to use.
   */
  private AmazonEC2 _ec2

  public AmazonEC2 getEC2() {
    if( !_ec2 ) {
      _ec2 = createEC2(credentials, regionEndpoints[region]['ec2'])
    }
  }

  public void setEC2(AmazonEC2 ec2) {
    _ec2 = ec2
  }

  /**
   * CloudFormation instance to use.
   */
  private AmazonCloudFormationAsync _cloudFormation

  public AmazonCloudFormationAsync getCloudFormation() {
    if( !_cloudFormation ) {
      _cloudFormation = createCloudFormation(credentials, regionEndpoints[region]['cloudformation'])
    }
    return _cloudFormation
  }
  
  public void setCloudFormation(AmazonCloudFormationAsync cf) {
    _cloudFormation = cf
  }
  
  Artifact getArtifact(ArtifactItem item) {
    if( !item )
      fail('Cannot get an Artifact instance for a null ArtifactItem.')

    // Fill in artifact version if missing
    if( !item.version ) {
      (project.dependencies + project.dependencyManagement.dependencies).find { dep ->
        [
          'groupId',
          'artifactId',
          'type'
        ].every {
          item."$it" == dep."$it"
        }
      }?.with { item.version = version }
    }

    // Complain if version still missing
    if( !item.version ) {
      fail("Unable to find artifact version of ${item.groupId}:${item.artifactId} in either projects dependencies or dependency management.")
    }

    // Create dependency
    Artifact artifact = artifactFactory.createDependencyArtifact(
        item.groupId,
        item.artifactId,
        VersionRange.createFromVersionSpec(item.version),
        item.type,
        item.classifier,
        Artifact.SCOPE_PROVIDED)

    // Resolve the artifact
    artifactResolver.resolve(
        artifact,
        project.remoteArtifactRepositories,
        artifactRepository)

    return artifact
  }

  def mapifyStack(Stack s) {
    def mapifyOutputs = { def outputs ->
      def m = [:]
      outputs.each { m[it.outputKey] = it.outputValue }
      return m
    }
    def mapifyParameters = { def parameters ->
      def m = [:]
      parameters.each { m[it.parameterKey] = it.parameterValue }
      return m
    }

    def m = [:]
    [
      'stackId',
      'stackStatus',
      'stackName'
    ].each {
      m[it] = s."$it"
    }
    mapifyOutputs(s.outputs).each { k, v -> m["outputs.${k}"] = v}
    mapifyParameters(s.parameters).each { k, v -> m["parameters.${k}"] = v }
    return m
  }
}
