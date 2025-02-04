/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package aws.sdk.kotlin.runtime.config.imds

// language=JSON
internal const val imdsTestSuite = """
{
  "tests": [
    {
      "name": "all fields unset, default endpoint",
      "env": {},
      "fs": {},
      "result": {
        "Ok": "http://169.254.169.254/latest/api/token"
      }
    },
    {
      "name": "environment variable endpoint override",
      "env": {
        "AWS_EC2_METADATA_SERVICE_ENDPOINT": "http://override:456"
      },
      "fs": {},
      "result": {
        "Ok": "http://override:456/latest/api/token"
      }
    },
    {
      "name": "profile endpoint override",
      "env": {
        "AWS_CONFIG_FILE": "config"
      },
      "fs": {
        "config": "[default]\nec2_metadata_service_endpoint = http://override:456"
      },
      "result": {
        "Ok": "http://override:456/latest/api/token"
      }
    },
    {
      "name": "environment overrides profile",
      "env": {
        "AWS_CONFIG_FILE": "config",
        "AWS_EC2_METADATA_SERVICE_ENDPOINT": "http://override:456"
      },
      "fs": {
        "config": "[default]\nec2_metadata_service_endpoint = http://wrong:456"
      },
      "result": {
        "Ok": "http://override:456/latest/api/token"
      }
    },
    {
      "name": "invalid endpoint mode override",
      "env": {
        "AWS_EC2_METADATA_SERVICE_ENDPOINT_MODE": "error"
      },
      "fs": {
      },
      "result": {
        "Err": "invalid EndpointMode: `error`"
      }
    },
    {
      "name": "ipv4 endpoint",
      "env": {
        "AWS_EC2_METADATA_SERVICE_ENDPOINT_MODE": "IPv4"
      },
      "fs": {
      },
      "result": {
        "Ok": "http://169.254.169.254/latest/api/token"
      }
    },
    {
      "name": "ipv6 endpoint",
      "env": {
        "AWS_EC2_METADATA_SERVICE_ENDPOINT_MODE": "IPv6"
      },
      "fs": {
      },
      "result": {
        "Ok": "http://[fd00:ec2::254]/latest/api/token"
      }
    },
    {
      "name": "case insensitive endpoint",
      "env": {
        "AWS_EC2_METADATA_SERVICE_ENDPOINT_MODE": "ipV6"
      },
      "fs": {
      },
      "result": {
        "Ok": "http://[fd00:ec2::254]/latest/api/token"
      }
    },
    {
      "name": "ipv6 endpoint from config",
      "env": {
        "AWS_CONFIG_FILE": "config"
      },
      "fs": {
        "config": "[default]\nec2_metadata_service_endpoint_mode = IPv6"
      },
      "result": {
        "Ok": "http://[fd00:ec2::254]/latest/api/token"
      }
    },
    {
      "name": "invalid config endpoint mode",
      "env": {
        "AWS_CONFIG_FILE": "config"
      },
      "fs": {
        "config": "[default]\nec2_metadata_service_endpoint_mode = IPv7"
      },
      "result": {
        "Err": "invalid EndpointMode: `IPv7`"
      }
    },
    {
      "name": "explicit endpoint override",
      "env": {
        "AWS_CONFIG_FILE": "config"
      },
      "fs": {
        "config": "[default]\nec2_metadata_service_endpoint_mode = IPv6"
      },
      "endpointOverride": "https://custom",
      "result": {
        "Ok": "https://custom/latest/api/token"
      }
    },
    {
      "name": "explicit mode override",
      "env": {
        "AWS_CONFIG_FILE": "config"
      },
      "fs": {
        "config": "[default]\nec2_metadata_service_endpoint_mode = IPv4"
      },
      "modeOverride": "IPv6",
      "result": {
        "Ok": "http://[fd00:ec2::254]/latest/api/token"
      }
    },
    {
      "name": "invalid uri",
      "env": {
        "AWS_EC2_METADATA_SERVICE_ENDPOINT": "not a uri"
      },
      "fs": {},
      "result": {
        "Err": "is not a valid inet host"
      }
    },
    {
      "name": "environment variable overrides endpoint mode override",
      "env": {
        "AWS_EC2_METADATA_SERVICE_ENDPOINT": "http://169.254.169.200"
      },
      "fs": {},
      "modeOverride": "IPv6",
      "result": {
        "Ok": "http://169.254.169.200/latest/api/token"
      }
    }
  ]
}
"""
