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

    def NumberCollector(File workingDir){
        this.db = DBHelper.setupDB(workingDir)
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
            jenkins: "insert into jenkins(month, instanceid, version, jvmvendor, jvmname, jvmversion) values(?, ?, ?, ?, ?, ?)",
            plugins: "insert into plugin(month, instanceid, name, version) values(?, ?, ?, ?)",
            jobTypes: "insert into job(month, instanceid, type, jobnumber) values(?, ?, ?, ?)",
            nodesOnOs: "insert into node(month, instanceid, osname, nodenumber) values(?, ?, ?, ?)",
            executors: "insert into executor(month, instanceid, numberofexecutors) values(?, ?, ?)",
        ]

        def data = [
            jenkins: new ArrayList<List>(),
            plugins: new ArrayList<List>(),
            jobTypes: new ArrayList<List>(),
            nodesOnOs: new ArrayList<List>(),
            executors: new ArrayList<List>()
        ]
        def insCnt = 0

        db.withTransaction {
            p.forEachInstance(file) { InstanceMetric metric ->
                if ((records++) % 1000 == 0)
                    System.out.print('.');
                def instId = metric.instanceId;

                data.jenkins << [instId, "${metric.jenkinsVersion}", "${metric.jvm?.vendor}", "${metric.jvm?.name}", "${metric.jvm?.version}"]

                metric.plugins.each { pluginName, pluginVersion ->
                    data.plugins << [instId, pluginName, pluginVersion]
                }

                metric.jobTypes.each { jobtype, jobNumber ->
                    data.jobTypes << [instId, jobtype, jobNumber]
                }

                metric.nodesOnOs.each { os, nodesNumber ->
                    data.nodesOnOs << [instId, os, nodesNumber]
                }

                data.executors << [instId, metric.totalExecutors]

                if (data.jenkins.size() > 50000) {
                    insCnt += batchInsert(queries, data, monthDate)
                    data = [
                        jenkins: new ArrayList<List>(),
                        plugins: new ArrayList<List>(),
                        jobTypes: new ArrayList<List>(),
                        nodesOnOs: new ArrayList<List>(),
                        executors: new ArrayList<List>()
                    ]
                }
            }
            insCnt += batchInsert(queries, data, monthDate)
            db.execute("insert into importedfile(name) values(${file.name})")
        }

        def commitTm = (new Date().getTime() - start.getTime()) / 1000
        timeByYear.put(year, timeByYear.get(year) + commitTm)
        recordsByYear.put(year, recordsByYear.get(year) + insCnt)
        println "\ncommitted ${records} records for ${monthDate.format('yyyy-MM')} in ${commitTm}"
        if (dateStr.endsWith("12")) {
            println "\nFOR YEAR ${year} WITH ${String.format("%,d", recordsByYear.get(year))} INSERTS TOTAL WAS ${timeByYear.get(year)}"
        }
        p = null
    }

    int batchInsert(Map<String,String> queries, Map<String,List<List>> data, monthDate) {
        def insCnt = 0
        queries.each { qType, qVal ->
            if (data[qType].size() > 0) {
                db.withBatch(15000, qVal) { ps ->
                    data[qType].each { d ->
                        insCnt++
                        d.add(0, monthDate)
                        ps.addBatch(d)
                    }
                }
            }
        }
        return insCnt
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
//Sql db = DBHelper.setupDB(workingDir)
new NumberCollector(workingDir).run(args)




