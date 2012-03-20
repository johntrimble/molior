package com.github.johntrimble.molior.maven.plugins

import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugin.MojoFailureException
import org.jfrog.maven.annomojo.annotations.MojoAggregator
import org.jfrog.maven.annomojo.annotations.MojoGoal
import org.jfrog.maven.annomojo.annotations.MojoParameter

import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.ec2.AmazonEC2
import com.amazonaws.services.route53.AmazonRoute53
import com.amazonaws.services.route53.AmazonRoute53Client
import com.amazonaws.services.route53.model.Change
import com.amazonaws.services.route53.model.ChangeBatch
import com.amazonaws.services.route53.model.ChangeInfo
import com.amazonaws.services.route53.model.ChangeResourceRecordSetsRequest
import com.amazonaws.services.route53.model.ChangeResourceRecordSetsResult
import com.amazonaws.services.route53.model.GetChangeRequest
import com.amazonaws.services.route53.model.HostedZone
import com.amazonaws.services.route53.model.ResourceRecord
import com.amazonaws.services.route53.model.ResourceRecordSet
import com.amazonaws.services.route53.model.ListResourceRecordSetsRequest

@MojoGoal('setCName')
@MojoAggregator
class SetCNameMojo extends AbstractMojo {
  @MojoParameter(required=true)
  public String name

  @MojoParameter(required=true)
  public String value

  @MojoParameter
  public String hostedZoneId

  @MojoParameter
  public String hostedZoneName

  AmazonRoute53 createRoute53(AWSCredentials credentials) {
    AmazonRoute53Client route53Client = new AmazonRoute53Client(credentials)
    return route53Client
  }

  void execute() throws MojoExecutionException, MojoFailureException {
    // Find the hosted zone
    AmazonRoute53 route53 = createRoute53(credentials)
    HostedZone zone = route53.listHostedZones().hostedZones.find({
      it.id == hostedZoneId || it.name == hostedZoneName
    })

    if( !zone ) {
      fail("Could not find a hosted zone matching ID '${hostedZoneId}' or '${hostedZoneName}'")
    }

    hostedZoneId = zone.id

    // Find all existing records for the domain that need to be removed
    List<ResourceRecordSet> recordsToRemove =
        route53.listResourceRecordSets(
        new ListResourceRecordSetsRequest(
        hostedZoneId: hostedZoneId,
        startRecordName: name,
        maxItems: '1000')).resourceRecordSets.findAll({
          it.type == 'CNAME' && it.name == name
        })
    
    // Collect records we need to create
    List<ResourceRecordSet> recordsToAdd = [new ResourceRecordSet(name: name, type: 'CNAME', tTL: 20, resourceRecords:[new ResourceRecord(value)])]
    
    // Apply changes
    List<Change> changes = recordsToRemove.collect({ new Change('DELETE', it) }) + recordsToAdd.collect({ new Change('CREATE', it) })
    ChangeResourceRecordSetsResult result = route53.changeResourceRecordSets(new ChangeResourceRecordSetsRequest(hostedZoneId, new ChangeBatch(changes)))
    
    // Poll changes until they've been propagated or we time out
    ChangeInfo changeInfo = result.changeInfo
    long startTime = System.currentTimeMillis()
    while( System.currentTimeMillis() - startTime < 60*2000 && 'INSYNC' != changeInfo.status ) {
      Thread.currentThread().sleep(20*1000)
      changeInfo = route53.getChange(new GetChangeRequest(changeInfo.id)).changeInfo
    }
    
    // Log what happened
    if( 'INSYNC' != changeInfo.status ) {
      log.info("Timed out when setting the domain name...")
    } else {
      log.info("Successfully set CNAME for ${name} to ${value}")
    }
  }
}
