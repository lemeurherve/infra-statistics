#!/usr/bin/env groovy
@Grab(group='com.gmongo', module='gmongo', version='1.5')
@GrabExclude("org.codehaus.groovy:groovy-xml")
@GrabExclude("org.codehaus.groovy:groovy-json")
@Grab(group='org.codehaus.jackson', module='jackson-core-asl', version='1.9.3')
@Grab(group='org.codehaus.jackson', module='jackson-mapper-asl', version='1.9.3')
import com.gmongo.GMongo
import com.mongodb.DBCollection

import groovy.json.*
import java.util.zip.*;

def parseArgs(cliArgs) {
    def cli = new CliBuilder(usage: "parse-usage.groovy [options]",
                             header: "Options")

    cli._(longOpt:'logs', args:1, required:true, "Directory containing raw logs")

    cli._(longOpt:'output', args:1, required:true, "Directory to output processed JSON to")

    cli._(longOpt:'timestamp', args:1, required:false, "Base timestamp for logs - i.e., '201112'")

    cli._(longOpt:'incremental', args:0, required:false, "Parse incrementally based on the available files in --logs and --output")
    
    def options = cli.parse(cliArgs)

    assert new File(options.logs).isDirectory(), "--logs value ${options.logs} is not a directory"
    assert new File(options.output).isDirectory(), "--output value ${options.output} is not a directory"

    return options
}


def argResult = parseArgs(this.args)
def logDir=new File(argResult.logs)
def outputDir=new File(argResult.output);

if (argResult.incremental) {
    def byMonth=[:] as TreeMap
    def re = /.*log\.([0-9]{6})[0-9]+\.(.*\.)?gz/
    logDir.eachFileMatch(~re) { f ->
        def m = (f=~re)
        if (m)  byMonth[m[0][1]] = true;
    }
    def data = byMonth.keySet() as List
    println "Found logs: ${data}"

    // do not process the current month as the data may not be complete yet
    data.pop()
    data.each { t ->
        if (new File(outputDir,"${t}.json.gz").exists()) {
            println "Skipping ${t}.json.gz as it already exists"
        } else {
            process(t,logDir,outputDir);
        }
    }
} else {
    // just process one month specified in the command line
    if (argResult.timestamp==null)
        throw new Error("Neither --incremental nor --timestamp was specified");
    process(argResult.timestamp, logDir, outputDir);
}

def process(String timestamp/*such as '201112'*/, File logDir, File outputDir) {
    def mongoConnection = new GMongo("127.0.0.1", 27017)
    def mongoDb = mongoConnection.getDB("test")
    DBCollection mColl = mongoDb.jenkins
    mColl.drop()

    def slurper = new JsonSlurper()

    def tmpDir = new File("./tmp")

    if (!tmpDir.isDirectory()) { 
        tmpDir.mkdirs()
    }

    def logRE = ".*log\\.${timestamp}.*gz"

    def linesSeen = 0
    def instCnt = [:]
    def dayCnt = [:]

    logDir.eachFileMatch(~/$logRE/) { origGzFile ->
        println "Handing original log ${origGzFile.canonicalPath}"

        new GZIPInputStream(new FileInputStream(origGzFile)).eachLine("UTF-8") { l ->
            linesSeen++;
            if (!dayCnt.containsKey(origGzFile.name)) {
                dayCnt[origGzFile.name] = [:]
            }
            def j = slurper.parseText(l)
            def installId = j.install
            def ver = j.version

            def jobCnt = j.jobs.values().inject(0) { acc, val -> acc+ val }

            if (jobCnt > 0) {
                mColl << j
                if (!instCnt.containsKey(installId)) {
                    instCnt[installId] = 0
                }
                instCnt[installId] += 1
                if (!dayCnt[origGzFile.name].containsKey(installId)) {
                    dayCnt[origGzFile.name][installId] = 0
                }
                dayCnt[origGzFile.name][installId] += 1
            }
            
        }
    }

    println "${mColl.count()} total reports with >0 jobs"
    mColl.createIndex([install: 1])
    println "${instCnt.keySet().size()} unique installs seen."

    File f = new File(outputDir, "report-grouping-${timestamp}")
    File cf = new File(outputDir, "report-count-${timestamp}")
    File df = new File(outputDir, "report-by-day-${timestamp}")
    def grouped = [:]
    instCnt.each { k, v ->
        if (v > 1) {
            if (!grouped.containsKey(v)) {
                grouped[v] = [k]
            } else {
                grouped[v].add(k)
            }
        }
    }

    grouped.sort {it.key}.each { k, v -> f.append("${k}: ${v}\n"); cf.append("${k}: ${v.size()}\n")}

    def otmp = new File(outputDir, "${timestamp}.json.tmp")
    otmp.withOutputStream() {os ->
        def w = new OutputStreamWriter(new GZIPOutputStream(os),"UTF-8");
        try {
            def builder = new StreamingJsonBuilder(w)

            builder {
                instCnt.keySet().each { String inst ->
                    def insts = mColl.find(install: inst)
                    if (insts.count() > 1) {
                        def toSave = []
                        insts.each { v ->
                            v.remove("_id")
                            toSave << v
                        }

                        "${inst}" toSave
                    }
                }
            }
        } finally {
            w.close();
        }
    }

    // when successfully completed, atomically produce the output
    otmp.renameTo(new File(outputDir, "${timestamp}.json.gz"))

    mongoConnection.close()
    mongoDb = null
    mColl = null
}
