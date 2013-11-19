## FAQ
### Why do I get an "Requires capabilities : [CAPABILITY_IAM]" error when creating a stack?
On 11/8/2013, Amazon updated the CreateStack operation to take an optional `Capabilities` parameter which should be set to `CAPABILITY_IAM` if the given template creates any IAM resources. However, it appears that even when a template does not contain any IAM resources that it is sometimes necessary to set the `Capabilities` parameter to `CAPABILITY_IAM` anyways. To do so, add the following configuration for the plugin to the POM.xml:

```
<capabilities>
  <capability>CAPABILITY_IAM</capability>
</capabilities>
```

This should work even if the AWS account used by the plugin does not have any IAM permissions, so long as the template does not actually specify any IAM resources. 