#!/usr/bin/env groovy

/*
 * Definition of the build/processing of Jenkins project usage statistics
 * <https://wiki.jenkins-ci.org/display/JENKINS/Usage+Statistics>
 */

final String JAVA_TOOL   = 'jdk8'
final String GROOVY_TOOL = 'groovy'
final String USAGE_HOST  = 'usage.jenkins.io'
final String CENSUS_HOST = 'census.jenkins.io'
final boolean isPRTest = !infra.isTrusted() && env.BRANCH_NAME

String nodeLabel = 'linux'

if (!isPRTest) {
    // Make sure we run this job once a month (at 3am on the 2nd of the month) when the raw stats are available.
    properties([pipelineTriggers([cron('0 3 2 * *')])])
    /* `census` is a node label for a single machine, ideally, which will be
     * consistently used for processing usage statistics and generating census data
     */
    nodeLabel = 'census'
}

node(nodeLabel) {
    /* grab our code from source control */
    checkout scm

    String javaHome = tool(name: 'jdk8')
    String groovyHome = tool(name: 'groovy')

    List<String> customEnv = [
        "PATH+JDK=${javaHome}/bin",
        "PATH+GROOVY=${groovyHome}/bin",
        "JAVA_HOME=${javaHome}",
    ]

    String usagestats_dir = './usage-stats'
    String census_dir = './census'
    String mongoDataDir = "mongo-data"

    stage('Sync raw data and census files') {
        if (isPRTest) {
            usagestats_dir = './testing/usage'
            census_dir = './testing/census'
            mongoDataDir = 'testing/mongo-data'

            // If we're running for a PR build, use a fresh testing directory and nuke whatever was there previously.
            sh "rm -rf testing"
            sh "mkdir -p ${usagestats_dir} ${census_dir}"
            sh "cd testing && tar -xvzf ../test-data/usage.tar.gz"
        }
        else {
            sh "rsync -avz --delete ${USAGE_HOST}:/srv/usage/usage-stats ."
            sh "rsync -avz --delete ${CENSUS_HOST}:/srv/census/census ."
        }
    }

    stage('Process raw logs') {
        // TODO: Fix ownership!
        // The mongo-data directory will end up containing files and dirs owned by 999:docker that we can't do much about for the moment.
        // Needs a better fix going forward, but for the moment...
        sh "mkdir -p ${mongoDataDir}"

        // Use the Mongo data directory in the workspace.
        docker.image('mongo:2').withRun('-p 27017:27017 -v ' + pwd() + "/" + mongoDataDir + ":/data/db") { container ->
            withEnv(customEnv) {
                sh "groovy parseUsage.groovy --logs ${usagestats_dir} --output ${census_dir} --incremental"
            }
        }
    }

    if (isPRTest) {
        // Make sure the expected files exist.
        sh "test -f ${pwd()}/testing/census/201103.json.gz"
        sh "test -f ${pwd()}/testing/census/201104.json.gz"
        sh "test -f ${pwd()}/testing/census/201105.json.gz"
    }

    stage('Generate census data') {
        withEnv(customEnv) {
            sh 'mkdir -p target'
            sh "groovy collectNumbers.groovy ${census_dir}/*.json.gz"
            sh 'groovy createJson.groovy'
        }
    }

    stage('Generate stats') {
        withEnv(customEnv) {
            sh "groovy generateStats.groovy ${census_dir}/*.json.gz"
        }
    }

    if (isPRTest) {
        stage('Archive artifacts') {
            archiveArtifacts 'target/svg/*, target/stats/*'
        }
    }
    else {
        stage('Publish census') {
            sh "rsync -avz ${census_dir} ${CENSUS_HOST}:/srv/census"
        }

        stage('Publish stats') {
            try {
                sh './publish-svgs.sh'
            }
            finally {
                sh 'git checkout master'
            }
        }
    }
}
