# Da Vinci US Drug Formulary Reference Server

This project is a reference FHIR server for the [Da Vinci US Drug Formulary
Implementation
Guide](https://build.fhir.org/ig/HL7/davinci-pdex-formulary/branches/stu2-draft//index.html). It is
based on the [HAPI FHIR JPA
Server](https://github.com/hapifhir/hapi-fhir-jpaserver-starter).

The server is hosted at https://davinci-drug-formulary-ri.logicahealth.org/fhir and it is **read only**.

## Running Locally

The easiest way to run this server is to use docker. 

First, clone this
repository. 

```
git clone git@github.com:HL7-DaVinci/drug-formulary-ri.git
cd drug-formulary-ri
```
Then, in `src/main/resources/hapi.properties`, set `server_address`
to `http://localhost:8080/fhir/`

Then, from the repository root, if on mac, run:

```
./build-docker-image.sh
docker-compose up
```
if on Windows, run:
```
./build-docker-image.bat
docker-compose up
```
Alternatively you can build the docker image and run it using normal docker commands:
```
docker build -t drug-formulary .
docker run -p 8080:8080 drug-formulary
```
The read only version of the server will be built, with the test data pre-loaded. The FHIR server will then be browsable at http://localhost:8080/fhir.

Alternatively, you can build, test and run this server with maven (and Java 8):

```
mvn clean install
mvn jetty:run
```


The server will then be browsable at
[http://localhost:8080/](http://localhost:8080/), and the
server's FHIR endpoint will be available at
[http://localhost:8080/fhir](http://localhost:8080/fhir).

This server supports both authenticated and unauthenticated access methods as described on the [implementation guide](https://build.fhir.org/ig/HL7/davinci-pdex-formulary/use_cases_and_overview.html#access-methods).

## Base URLs
**Hosted Server**:  https://davinci-drug-formulary-ri.logicahealth.org/fhir

**Local Server**: http://localhost:8080/fhir
## Unauthenticated FHIR Services
The following endpoints are publicly available, so do not require authentication or authorization.
| Service                                                                       | Methods  | Description                                                                                                                                                                                                        |
| ----------------------------------------------------------------------------- | -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `/metadata`                                                                   | `GET`    | The FHIR [capabilities interaction](http://hl7.org/fhir/R4/http.html#capabilities) that returns a FHIR [CapabilityStatement](http://hl7.org/fhir/R4/capabilitystatement.html) resource describing these services.  |
| `/InsurancePlan?type=http://hl7.org/fhir/us/davinci-pdex-plan-net/CodeSystem/InsuranceProductTypeCS\|`                                        | `GET`    | The FHIR [InsurancePlan](http://hl7.org/fhir/R4/insurancePlan.html) endpoint returns all the `PayerInsurancePlans` from the server.                                                       |
| `/InsurancePlan?type=http://terminology.hl7.org/CodeSystem/v3-ActCode\|DRUGPOL`                        | `GET`    | The FHIR [InsurancePlan](http://hl7.org/fhir/R4/insurancePlan.html) endpoint returns all the `Formulary resource` from the server.                               |
|`/Basic?code=http://hl7.org/fhir/us/davinci-drug-formulary/CodeSystem/usdf-InsuranceItemTypeCS\|formulary-item&formulary=InsurancePlan/FormularyD1002&_include=Basic:subject`  | `GET  `   | The FHIR [Basic](http://hl7.org/fhir/R4/basic.html) endpoint that returns all FormularyItems and FormularyDrugs in a Formulary.|
|`/MedicationKnowledge`| `GET` | The [MedicationKnowledge](https://build.fhir.org/ig/HL7/davinci-pdex-formulary/StructureDefinition-usdf-FormularyDrug.html) resource  provides the drug information which is part of a formulary.|
|||

Check more anticipated queries [here](https://build.fhir.org/ig/HL7/davinci-pdex-formulary/queries.html#anticipated-client-queries).

## Patient Access (Authenticated Access)
In compliance with the Centers for Medicare and Medicaid Services (“CMS”) Interoperability and Patient Access Final Rule (CMS-9115-F), this server allows users to access their formulary information using registered, third-party client applications.

The protected resources are `Patient` and `Coverage`.

### Registration
To get access to the Patient Access service, you can register your client app at this endpoint: `/oauth/register/client`. Once you submit your client's redirect URI, the server will generate and assign a client id and secret to your app.

### OAuth
This server requires getting an access token via the [SMART's Standalone Launch Sequence](https://hl7.org/fhir/smart-app-launch/example-app-launch-symmetric-auth.html#step-5-access-token) before making requests to the protected resources. The authorization and token endpoints can be found at the Capability Statement endpoint `/metadata` or the Smart Configuration endpoint `/.well-known/smart-configuration`.
1. Request Access:

      The authorization endpoint is `/oauth/authorization` and the required query parameters are:
      
      | Parameter | Value |
      |----------------------- | --------------------------------- |
      | response_type	| code |
      | client_id	    | The client id |
      | redirect_uri	| The URI to redirect to with the code |
      | scope	 |[The SMART on FHIR Access Scope](http://www.hl7.org/fhir/smart-app-launch/scopes-and-launch-context/index.html) |
      | state	 | Unique ID generated by the client for this interaction |
      | aud	   | The fhir base URL for this formulary server |

      Request Example:
      ```
      GET {base url}/oauth/authorization?
      response_type=code
      &client_id={client_id}
      &redirect_uri={your client's redirect uri}
      &scope={scope}
      &state={unique string}
      &aud={base url}
      ```
      Once you submit the `GET` request to the authorization endpoint, you will be prompted to login. The following is the test user credentials you can use for testing:
      ```
      username: PDexPatient
      password: password
      ```
      If the user's credentials and query params from the authorization request are validated, the server will redirect the browser back to client's redirect uri provided, with the authorization code and state query parameters.

      Example response
      ```
      {client's redirect uri}?
      code=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9
      &state=0hJc1S9O4oW54XuY
      ```
      The authorization code is a signed JWT token and is only valid for **2 minutes**.

2. Retrieve Access Token:
      
      The token endpoint is `/oauth/token`. The client must issue a `POST` request to the server following the `authorization_code` OAuth 2.0 grant flow process, and including a basic Authorization header with the value `base64Encode(client_id:client_secret)` and use `Content-Type` of `application/x-www-form-urlencoded`.
      ```
      POST HTTP/1.1
      Authorization: Basic {base64 encoded token}
      Content-Type: application/x-www-form-urlencoded
      {base url}/oauth/token?grant_type=authorization_code
      &code={Authorization code retrieved in previous step }
      &redirect_uri={client's redirect uri}
      ```
      The successful response will be a JSON object containing the `access_token`, `token_type`, `expires_in`, `patient`, `scope`, and `refresh_token`. 
      ```json
      {
          "access_token": "{signed JWT token}",
          "token_type": "Bearer",
          "expires_in": 3600,
          "patient": "{the user's patient id}",
          "scope": "{the server's supported scopes}",
          "refresh_token": "{signed JWT token}"
      }
      ```
      The access token token will only be valid for **an hour**. In all requests to the server for patient access, the client app must add the `Authorization: Bearer {access_token}` header to the HTTP request.

      > For testing purposes an admin token is available for clients that do not support this workflow yet. The admin token is 
      ```
      eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJodHRwczovL2RhdmluY2ktZHJ1Zy1mb3JtdWxhcnktcmkubG9naWNhaGVhbHRoLm9yZy9maGlyIiwiaWF0IjoxNjQ1MTMzNDU0LCJleHAiOjE3Mzk4Mjc4NTQsImF1ZCI6Imh0dHBzOi8vZGF2aW5jaS1kcnVnLWZvcm11bGFyeS1yaS5sb2dpY2FoZWFsdGgub3JnL2ZoaXIiLCJzdWIiOiJhZG1pbiIsInBhdGllbnRfaWQiOiJhZG1pbiJ9.cLvTTpGH5lxXMjwsPN-1NPo9jUuc6C43FcjH8s81VA5kXdmRdQhAww2oO_i_IOTUnOVaIU-JU9Ygd3MBXckPnVlrLiN_Dtdb_71DcqpVJflc9FAqorcUGmaE5qg-nZVI_sKofPaliYxUeBriTgwS06VtILl2k2WylAD83LbDjBCCq4MBHlaWSCqc9LRKJT3Ez3D93IPWgwBgPT46cIML6_PdwLO5Zl5XkEyXLjsUUAuraPM-dET7tw6KLr0gbd6Xdj2BuZLZjYmxTOWry_n6hAiQDTQ2iaLtwp2rtZlfbX5L38cpri-TRKN1l7EeNguJXk1dVfPPv78_5qqhqg71jQ
      ```

### Querying User's Drug Coverage and Formulary
The user's drug coverage can be queried by calling the coverage endpoint `/Coverage` with the `patient` and coverage `type` parameters. Drug coverage can be searched for with a `coverage.type` of `http://terminology.hl7.org/CodeSystem/v3-ActCode|DRUGPOL`. The patient id provided in the request must match the logged in user's patient id.

**Sample request**:
```
GET HTTP/1.1
  Authorization: Bearer {your access token}
  Content-Type: application/json
  {base url}/Coverage?
  patient={logged in user's patient id}
  &type=http://terminology.hl7.org/CodeSystem/v3-ActCode|DRUGPOL
```
This will return a `Bundle` containing the user's drug coverage.

Once you retrieve the drug coverage resource, you can get the `Formulary plan identifier` from the corresponding `Coverage.class.value`.

**Coverage Resource Snippet**:
```json
       "class": [
  {
    "type": {
      "coding": [
        {
          "system": "http://terminology.hl7.org/CodeSystem/coverage-class",
          "code": "plan"
        }
      ]
    },
    "value": "Formulary-10207VA0380001",
    "name": "BlueChoice HMO Silver $3,500"
  }
]
```
With the `Formulary` identifier retrieved, the user's formulary can be queried as follow:
> Note: access token is not required to query the `/InsurancePlan` endpoint.
```
 GET [base]/InsurancePlan?
 type=http://terminology.hl7.org/CodeSystem/v3-ActCode|DRUGPOL
 &identifier=Formulary-10207VA038000
```

## Contributions
This project welcomes Pull Requests. Any issues identified with the RI should be submitted via the [GitHub issue tracker](https://github.com/HL7-DaVinci/drug-formulary-ri/issues).
## Troubleshooting
This reference implimentation has only been tested with `java 8`. A common error encountered when manually buiding this server locally with another java version is related to `.m2/repositories/com/h2database`. If you encounter this issue verify you are using Java 8, delete the h2database folder and run the server again.

* ### Debug Options

    There are a few debug endpoints to help with debugging issues related to authorization. They are helpful to retrieve your client's credentials, see a list of test users, or update your client's redirect URI.

    Endpoint  |  Description
    --------- | --------------
    `/debug/Clients` | `GET` a list of registered clients
    `/debug/Users` | `GET` a list of available test users
    `/debug/UpdateClient?client_id={client_id}` | `PUT` request to update client
    `/Log` | `GET` the server log

  **Request body for updateClient request**:
  ```json
  {
    "id": "{id}",
    "secret": "{secret}",
    "redirectUri": "{redirect uri}"
  }
  ```

