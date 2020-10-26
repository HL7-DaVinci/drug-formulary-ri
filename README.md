# Drug Formulary Reference Server

This is the write branch of the Drug Formulary Repo. It allows the data to be updated. THe HAPI Server on this branch is a vanilla 4.1.0 HAPI Server. Follow the steps below to update the data.

## Updating the server

1. Usually, you will want to remove all the data from the server with `rm -rf data` so the server starts with a blank slate. Skip this step if you want to retain the existing data.
1. Start the server by running `mvn jetty:run`. Note you will probably need to be using JDK8 for this to work.
1. In a separate terminal upload the data using the `upload.rb` script found in the [pdex-formulary-sample-data](https://github.com/HL7-DaVinci/pdex-formulary-sample-data) repo.
1. Once the upload is completed (note this may take a while since there are over 20,000 resources), use `CTRL+c` to stop the server.
1. Stage the new data with `git add data`.
1. Commit the data with `git commit -m 'update data'`.
1. Now it is necessary to copy this data into the master branch. First, copy the data to a new folder which isn't tracked by git `cp -r data data2`.
1. Change to the `master` branch `git checkout master`.
1. Remove the old data `rm -rf data`.
1. Move the new data to the correct location `mv data2 data`.
1. Stage the new data with `git add data`.
1. Commit the data with `git commit -m 'update data'`.
1. Push up your changes with `git push`. The server will automatically redeploy on LogicaHealth/
