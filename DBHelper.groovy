import groovy.sql.Sql
import org.sqlite.*
import org.sqlite.SQLiteConfig
import java.sql.*

class DBHelper {
    static boolean dbExists

    static Sql setupDB(File workingDir){

        def dbFile = new File(new File(workingDir, "statsdb"), "stats.db")

        dbExists = dbFile.exists()

        // in memory
        // db = groovy.sql.Sql.newInstance("jdbc:sqlite::memory:","org.sqlite.JDBC")

        // persistent
        SQLiteConfig config = new SQLiteConfig()
        config.setCacheSize(4000000)
        config.setSynchronous(SQLiteConfig.SynchronousMode.OFF)
        config.setLockingMode(SQLiteConfig.LockingMode.EXCLUSIVE)
        config.setJournalMode(SQLiteConfig.JournalMode.OFF)
        config.setTempStore(SQLiteConfig.TempStore.MEMORY)

        def db = new Sql(config.createConnection("jdbc:sqlite:"+dbFile.absolutePath))
        db.setCacheStatements(true)

        if(!dbExists){
            // define the tables
            db.execute("create table jenkins(instanceid, month, version, jvmvendor, jvmname, jvmversion)")
            db.execute("create table plugin(instanceid, month, name, version)")
            db.execute("create table job(instanceid, month, type, jobnumber)")
            db.execute("create table node(instanceid, month, osname, nodenumber)")
            db.execute("create table executor(instanceid, month, numberofexecutors)")
            db.execute("create table importedfile(name)")
            db.execute("CREATE INDEX plugin_name on plugin (name)")
            db.execute("CREATE INDEX jenkins_version on jenkins (version)")
            db.execute("CREATE INDEX plugin_month on plugin (month)")
            db.execute("CREATE INDEX plugin_namemonth on plugin (name,month)")
        }

        return db;
    }


    /**
     * is the file with the given name already imported?
     */
    static boolean doImport(db, fileName){
        if(db){
            def filePrefix = fileName.substring(0, fileName.indexOf("."))+"%"
            def rows = db.rows("select name from importedfile where name like $filePrefix;")
            return rows.size() == 0
        }
        true
    }

}



