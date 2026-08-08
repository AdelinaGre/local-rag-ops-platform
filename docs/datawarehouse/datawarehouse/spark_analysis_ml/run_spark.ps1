param(
    [Parameter(Mandatory = $true)]
    [string]$ScriptPath,

    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$ScriptArguments
)

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path

$PythonExe = Join-Path $Root ".venv\Scripts\python.exe"
$SparkHome = Join-Path $Root ".venv\Lib\site-packages\pyspark"
$SparkSubmit = Join-Path $Root ".venv\Scripts\spark-submit.cmd"

$IvyJars = Join-Path $env:USERPROFILE ".ivy2.5.2\jars"
$MongoJars = @(
    (Join-Path $IvyJars "org.mongodb.spark_mongo-spark-connector_2.13-10.5.0.jar"),
    (Join-Path $IvyJars "org.mongodb_mongodb-driver-sync-5.1.4.jar"),
    (Join-Path $IvyJars "org.mongodb_mongodb-driver-core-5.1.4.jar"),
    (Join-Path $IvyJars "org.mongodb_bson-5.1.4.jar"),
    (Join-Path $IvyJars "org.mongodb_bson-record-codec-5.1.4.jar")
)

if (-not (Test-Path $PythonExe)) {
    throw "Python executable not found: $PythonExe. Create the venv first with: python -m venv .venv"
}

if (-not (Test-Path (Join-Path $SparkHome "jars"))) {
    throw "PySpark jars directory not found: $SparkHome\jars. Install pyspark with: python -m pip install pyspark"
}

if (-not (Test-Path $SparkSubmit)) {
    throw "spark-submit.cmd not found: $SparkSubmit. Reinstall pyspark inside .venv."
}

$MissingJars = $MongoJars | Where-Object { -not (Test-Path $_) }
if ($MissingJars.Count -gt 0) {
    throw "Mongo Spark Connector jars not found in $IvyJars. Missing: $($MissingJars -join ', ')"
}

$env:PYSPARK_PYTHON = $PythonExe
$env:PYSPARK_DRIVER_PYTHON = $PythonExe
$env:SPARK_HOME = $SparkHome
$env:PATH = (Join-Path $Root ".venv\Scripts") + ";" + $env:PATH

$MongoClasspath = $MongoJars -join ";"

& $SparkSubmit `
    --driver-class-path $MongoClasspath `
    --conf "spark.executor.extraClassPath=$MongoClasspath" `
    $ScriptPath `
    @ScriptArguments
