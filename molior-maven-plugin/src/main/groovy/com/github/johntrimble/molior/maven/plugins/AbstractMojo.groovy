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
import org.apache.maven.project.MavenProject
import org.codehaus.gmaven.common.ArtifactItem
import org.codehaus.gmaven.mojo.GroovyMojo
import org.codehaus.plexus.components.interactivity.Prompter

import com.amazonaws.auth.AWSCredentials
import com.amazonaws.auth.PropertiesCredentials
import com.amazonaws.http.AmazonHttpClient
import com.amazonaws.services.cloudformation.AmazonCloudFormation;
import com.amazonaws.services.cloudformation.AmazonCloudFormationClient
import com.amazonaws.services.ec2.AmazonEC2
import com.amazonaws.services.ec2.AmazonEC2Client
import com.amazonaws.services.cloudformation.model.Stack

abstract class AbstractMojo extends GroovyMojo {
  static {
    // The AWS bindings tend to do a lot of logging... disable it.
    LogManager.getLogManager().reset()
  }
  
  /**
   * @parameter expression="${project}"
   * @required
   * @readonly
   *
   * @noinspection UnusedDeclaration
   */
  protected MavenProject project;

  /**
   * @component
   * @readonly
   * @required
   *
   * @noinspection UnusedDeclaration
   */
  ArtifactFactory artifactFactory

  /**
   * @component
   * @readonly
   * @required
   *
   * @noinspection UnusedDeclaration
   */
  ArtifactResolver artifactResolver

  /**
   * @component
   * @readonly
   * @required
   *
   * @noinspection UnusedDeclaration
   */
  ArtifactMetadataSource artifactMetadataSource

  /**
   * @parameter expression="${localRepository}"
   * @readonly
   * @required
   *
   * @noinspection UnusedDeclaration
   */
  ArtifactRepository artifactRepository

  /**
   * @parameter expression="${project.pluginArtifactRepositories}"
   * @required
   * @readonly
   *
   * @noinspection UnusedDeclaration
   */
  List remoteRepositories

  /**
   * Component used to prompt for input.
   * @component
   * @readonly
   * @required
   */
  protected Prompter prompter

  void setPrompter( Prompter prompter ) {
    this.prompter = prompter;
  }

  /**
   * @parameter default-value="true"
   */
  boolean interactive
  
  /**
   * File containing AWS credentials in the following format:
   * accessKey=YOUR_ACCESS_KEY
   * secretKey=YOUR_SECRET_KEY
   *
   * @parameter expression="${user.home}/.aws_credentials"
   */
  File credentialsFile

  /**
   * The AWS region to use.
   *
   * @parameter default-value="us-west-1"
   */
  String region

  // Mapping of regions to AWS endpoints.
  private Map regionEndpoints = [
    'us-west-1': [
      'cloudformation': 'cloudformation.us-west-1.amazonaws.com',
      'ec2': 'ec2.us-west-1.amazonaws.com'],
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
    AmazonCloudFormationClient cf = new AmazonCloudFormationClient(credentials)
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
    if( !credentialsFile.exists() )
      fail("Could not find credentials file ${credentialsFile.path}")
    new PropertiesCredentials(credentialsFile)
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
  private AmazonCloudFormation _cloudFormation

  public AmazonCloudFormation getCloudFormation() {
    if( !_cloudFormation ) {
      _cloudFormation = createCloudFormation(credentials, regionEndpoints[region]['cloudformation'])
    }
    return _cloudFormation
  }

  public void setCloudFormation(AmazonCloudFormation cf) {
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
