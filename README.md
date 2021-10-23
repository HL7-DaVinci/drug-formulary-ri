# Drug Formulary Reference Server

This is the write branch of the Drug Formulary Repo. It allows the data to be updated. THe HAPI Server on this branch is a vanilla 4.1.0 HAPI Server. Follow the steps below to update the data.

## Updating the server

1. Usually, you will want to remove all the data from the server with `rm -rf data` so the server starts with a blank slate. Skip this step if you want to retain the existing data.
1. If you need to build the docker image, run `./build-docker-image.sh` first. If you have recreated the image since the last time `docker-compose` was run you will be prompted to create a new image (click `y`).
1. Run `docker-compose up` to start the server.
1. In a separate terminal upload the data using the `upload.rb` script found in the [pdex-formulary-sample-data](https://github.com/HL7-DaVinci/pdex-formulary-sample-data) repo.
1. NOTE: The HitHub has a maximum single file size of 100MB. The data/database/h2.mv.db can easily surpass that limit with the full data set. Reduce the number to 3 Insurance Plans (removing the other associated PayerInsurancePlan, Formulary, and FormularyItem resources)
1. Once the upload is completed (note this may take a while since there are over 20,000 resources), use `CTRL+c` or run `docker-compose down` to stop the server.
1. After the upload is complete, the indexing may not be performed or completed for some time. To force running, run the operations: `http://localhost:8080/fhir/$mark-all-resources-for-reindexing` then `http://localhost:8080/fhir/$perform-reindexing-pass`, let the server re-index for a while and test.
1. Stage the new data with `git add data`.
1. Commit the data with `git commit -m 'update data'`.
1. Now it is necessary to copy this data into the master branch. First, copy the data to a new folder which isn't tracked by git `cp -r data data2`.
1. Change to the `master` branch `git checkout master`.
1. Remove the old data `rm -rf data`.
1. Move the new data to the correct location `mv data2 data`.
1. Stage the new data with `git add data`.
1. Commit the data with `git commit -m 'update data'`.
1. Push up your changes with `git push`. The server will automatically redeploy on LogicaHealth/
