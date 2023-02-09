# Apache Guacamole on Google Cloud Platform

See the tutorial at [Google Cloud Architecture Center](https://cloud.google.com/architecture/deploy-guacamole-gke)

This is not an officially supported Google product



Guacamole provides an extension framework - guacamole-ext - which can be used to provide custom authentication mechanisms. The guacamole-auth-googleiap extension is used in conjunction with a database authentication mechanism (Google CloudSQL), applied in the following order:

* <strong>guacamole-auth-googleiap</strong> (custom service) - validates the Json Web Token (JWT) passed by IAP.  It cryptographically validates this token and ensures its validity. The identity is extracted from the JWT and used as the username passed to the next mechanism in the Guacamole authentication chain.
* <strong>guacamole-auth-jdbc-mysql</strong> (built-in service) - this service trusts the identity passed from guacamole-auth-googleiap and compares this with information in the database to perform authorization, as well as storing profile information about that identity.

In order for the extension to properly validate the JWT, it checks that IAP issued the token for Guacamole. The extension checks this by inspecting the JWT headers and payload, and validating the token’s digital signature. For more information about IAP token validations, see Securing your app with signed headers.
To verify the token was issued for Guacamole, the extension compares the token’s aud (Audience) claim, to the backendServiceId which is part of the Guacamole’s Load Balancer configuration, created by GKE. The extension uses Google Cloud API to retrieve the backend service ID programmatically from your project, and authenticates to the API with the GKE Workload Identity you configured earlier.

