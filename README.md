# Da Vinci US Drug Formulary Reference Server

This project is a reference FHIR server for the [Da Vinci US Drug Formulary
Implementation
Guide](http://build.fhir.org/ig/HL7/davinci-pdex-formulary/index.html). It is
based on the [HAPI FHIR JPA
Server](https://github.com/hapifhir/hapi-fhir-jpaserver-starter).

# Running Locally

The easiest way to run this server is to use docker. First, clone this
repository. Then, from the repository root run:

```
docker build -t drug-formulary .
```

This will build the docker image for the reference server. Once the image has
been built, the server can be run with the following command:

```
docker run -p 8080:8080 drug-formulary
```

The server will then be browseable at
[http://localhost:8080/formulary](http://localhost:8080/formulary), and the
server's FHIR endpoint will be available at
[http://localhost:8080/formulary/fhir](http://localhost:8080/formulary/fhir)
