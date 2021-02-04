#!/usr/bin/env groovy
// push *.json.gz into a local SQLite database
import org.sqlite.*
import groovyx.gpars.GParsPool
import static groovyx.gpars.GParsPool.withPool
import groovy.sql.Sql

@Grapes([
    @Grab(group='org.codehaus.jackson', module='jackson-mapper-asl', version='1.9.13'),
    @Grab('org.xerial:sqlite-jdbc:3.34.0'),
    @Grab('org.codehaus.gpars:gpars:1.2.1'),
    @GrabConfig(systemClassLoader=true)
])



class NumberCollector {

    Sql db
    File workingDir
    Map<String,BigDecimal> timeByYear = [:]
    Map<String,Integer> recordsByYear = [:]

    def NumberCollector(File workingDir, Sql db){
        this.db = db
        this.workingDir = workingDir
    }

    def generateStats(File file) {
        if (!DBHelper.doImport(db, file.name)) {
            println "skip $file - already imported..."
            return
        }

        def start = new Date()
        def year = file.name.substring(0, 4)
        if (!timeByYear.containsKey(year)) {
            timeByYear[year] = new BigDecimal(0)
            recordsByYear[year] = 0
        }
        def dateStr = file.name.substring(0, 6)
        def monthDate = Date.parse('yyyyMM', dateStr)
        int records=0;

        JenkinsMetricParser p = new JenkinsMetricParser()

        def queries = [
            jenkins: [
                query: "insert into jenkins(instanceid, month, version, jvmvendor, jvmname, jvmversion) values( ?, ?, ?, ?, ?, ?)",
                data: [],
            ],
            plugins: [
                query: "insert into plugin(instanceid, month, name, version) values( ?, ?, ?, ?)",
                data: [],
            ],
            jobTypes: [
                query: "insert into job(instanceid, month, type, jobnumber) values(?, ?, ?, ?)",
                data: [],
            ],
            nodesOnOs: [
                query: "insert into node(instanceid, month, osname, nodenumber) values(?, ?, ?, ?)",
                data: [],
            ],
            executors: [
                query: "insert into executor(instanceid, month, numberofexecutors) values(?, ?, ?)",
                data: [],
            ]
        ]

        p.forEachInstance(file) { InstanceMetric metric ->
            if ((records++)%1000==0)
                System.out.print('.');
            def instId = metric.instanceId;

            queries['jenkins']['data'] << [instId, "${monthDate}", "${metric.jenkinsVersion}", "${metric.jvm?.vendor}", "${metric.jvm?.name}", "${metric.jvm?.version}"]

            metric.plugins.each { pluginName, pluginVersion ->
                queries['plugins']['data'] << [instId, "${monthDate}", pluginName, pluginVersion]
            }

            metric.jobTypes.each { jobtype, jobNumber ->
                queries['jobTypes']['data'] << [instId, "${monthDate}", jobtype, jobNumber]
            }

            metric.nodesOnOs.each { os, nodesNumber ->
                queries['nodesOnOs']['data'] << [instId, "${monthDate}", os, nodesNumber]
            }

            queries['executors']['data'] << [instId, "${monthDate}", metric.totalExecutors]
        }

        def insCnt = 0

        db.withTransaction({

            queries.each { qType, qVal ->
                db.withBatch(25000, qVal['query']) { ps ->
                    qVal['data'].each { d ->
                        insCnt++
                        ps.addBatch(d)
                    }
                }
            }
            db.execute("insert into importedfile(name) values(${file.name})")
        })

        def commitTm = (new Date().getTime() - start.getTime()) / 1000
        timeByYear.put(year, timeByYear.get(year) + commitTm)
        recordsByYear.put(year, recordsByYear.get(year) + insCnt)
        println "\ncommitted ${records} records for ${monthDate.format('yyyy-MM')} in ${commitTm}"
        if (dateStr.endsWith("12")) {
            println "\nFOR YEAR ${year} WITH ${recordsByYear.get(year)} RECORDS TOTAL WAS ${timeByYear.get(year)}"
        }
    }

    def run(String[] args) {
        if (args.length==0) {
            workingDir.eachFileMatch( ~".*json.gz" ) { file -> generateStats(file) }
        } else {
            args.each { f -> generateStats(new File(f)) }
        }
    }
}

def workingDir = new File("target")
Sql db = DBHelper.setupDB(workingDir)
new NumberCollector(workingDir, db).run(args)




