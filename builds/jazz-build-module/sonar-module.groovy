#!groovy?
import groovy.json.JsonSlurperClassic
import groovy.json.JsonOutput
import groovy.transform.Field

echo "Sonar code analyzer module loaded successfully"

/**
 * The Sonar module to run static code analysis - for JAVA, NodeJs & Python
*/

@Field def config_loader
@Field def service_config
@Field def g_branch
@Field def g_sonar_projectKey = ""
@Field def g_sonar_projectVersion = "1.0"
@Field def g_sonar_sources = "."
@Field def g_sonar_java_binaries = "target/"
@Field def g_sonar_java_coverage_reports = "target/site/jacoco/jacoco.xml"
@Field def g_sonar_login = ""
@Field def g_sonar_password = ""
@Field def g_sonar_project_properties
@Field def g_dependencyCheckOutputFileName = "dependency-check-report.xml"
@Field def g_dependency_check_properties = [:]
@Field def g_sonar_exclusions = "**/dist/**,**/jazz-modules/**,**/virtualenv/**,**/venv/**,**/*.json,**/*.spec.js,**/vendor/**,**/pkg/**,**/library/**,**/node_modules/**,**/coverage/**,**/target/**"

/**
 * Configure sonar and create the map for project specific sonar
 * properties
 *
 */
def configureSonarProperties() {
	try {
		g_sonar_project_properties = [:]
		def projectKey = getSonarProjectKey()
		setProjectKey(projectKey)

		g_sonar_project_properties["sonar.host.url"] = "http://${config_loader.CODE_QUALITY.SONAR.HOST_NAME}"
		g_sonar_project_properties["sonar.login"] = g_sonar_login
		g_sonar_project_properties["sonar.password"] = g_sonar_password
		g_sonar_project_properties["sonar.projectKey"] = g_sonar_projectKey
		g_sonar_project_properties["sonar.projectName"] = g_sonar_projectKey
		g_sonar_project_properties["sonar.projectVersion"] = g_sonar_projectVersion
		g_sonar_project_properties["sonar.sources"] = g_sonar_sources
		g_sonar_project_properties["sonar.exclusions"] = g_sonar_exclusions

		if (service_config['runtime'].indexOf("java") > -1) {
			g_sonar_project_properties["sonar.java.binaries"] = g_sonar_java_binaries
			g_sonar_project_properties["sonar.coverage.jacoco.xmlReportPaths"] = g_sonar_java_coverage_reports
		}

		if (service_config['runtime'].indexOf("go") > -1) {
			g_sonar_project_properties["sonar.tests"] = "."
			g_sonar_project_properties["sonar.test.inclusions"] = "**/*_test.go"
			g_sonar_project_properties["sonar.go.coverage.reportPaths"] = "cov.out"
		}

		if (service_config['runtime'].indexOf("node") > -1) {
			g_sonar_project_properties["sonar.javascript.lcov.reportPaths"] = "coverage/lcov.info"
		}

		if (service_config['runtime'].indexOf("python") > -1) {
			g_sonar_project_properties["sonar.python.coverage.reportPath"] = "coverage.xml"
		}

		if (service_config['framework'] && service_config['framework'].indexOf("angular") > -1) {
			g_sonar_project_properties["sonar.javascript.lcov.reportPaths"] = "app/coverage/lcov.info"
		}

		if (service_config['framework'] && service_config['framework'].indexOf("react") > -1) {
			g_sonar_project_properties["sonar.javascript.lcov.reportPaths"] = "app/coverage/lcov.info"
		}

		if (config_loader.CODE_QUALITY.SONAR.JAZZ_PROFILE) {
			g_sonar_project_properties["sonar.profile"] = config_loader.CODE_QUALITY.SONAR.JAZZ_PROFILE
		}

		if (config_loader.CODE_QUALITY.SONAR.ENABLE_VULNERABILITY_SCAN && config_loader.CODE_QUALITY.SONAR.ENABLE_VULNERABILITY_SCAN == "true") {
			g_sonar_project_properties["sonar.dependencyCheck.reportPath"] = g_dependencyCheckOutputFileName
		}

	} catch (ex) {
		error "configureSonarProperties Failed. " + ex.getMessage()
	}
}


def getSonarProjectKey(){
	if (service_config['service'] && g_branch) {
		def projectKey = service_config['service'] + "_" + g_branch.replaceAll("/", "-")
		if (service_config['domain']) {
			projectKey = service_config['domain'] + "_" + projectKey
		}
		projectKey = "${config_loader.CODE_QUALITY.SONAR.KEY_PREFIX}_${projectKey}"
		return projectKey
	} else {
		error "Invalid project configurations for Sonar"
	}
}

/**
 * Initialization for static code analysis
 * @param configData - Configuration Data
 * @param service - Service name
 * @param domain - domain
 * @param branch - current branch being built
 * @param runtime - specify the service runtime
 */

def initialize(configLoader, serviceConfig, branch) {
	setServiceConfig(serviceConfig)
	setConfigLoader(configLoader)
	setBranch(branch)
	withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: config_loader.CODE_QUALITY.SONAR.ADMIN_SONAR_CREDENTIAL_ID, passwordVariable: 'PWD', usernameVariable: 'UNAME']]) {
		setCredentials(UNAME, PWD)
	}
}

/**
 * Configure the credentials for Sonar
 *
 */
def setCredentials(username, password) {
	if (username && password) {
		setUserName(username)
		setPassword(password)
	} else {
		error "Not sufficient input for Sonar scanner configuration"
	}

}

/**
 * Run the scanner for code analysis report based on the project settings
 *
 */
def runReport() {
	try {
		cleanUpWorkspace()
		generateCoverageReports()
		def sonar_scanner_cl = "sonar-scanner"
		for (item in g_sonar_project_properties) {
			sonar_scanner_cl += " -D${item.key}=${item.value} "
		}
		jazz_quiet_sh(sonar_scanner_cl)
	} catch (ex) {
		error "runReport failed: " + ex.getMessage()
	}
}

/**
 * Clean up the build workspace folder for fresh code analysis
 * docker instances will be reused based on availability which may come with build artifacts from
 * previous builds.
 */
def cleanUpWorkspace() {
	if (service_config['runtime'].indexOf("nodejs") > -1) {
		sh "rm -rf ./node_modules"
	} else if (service_config['runtime'].indexOf("java") > -1) {
		sh "rm -rf ./target"
		sh "mvn compile"
	} else if (service_config['runtime'].indexOf("python") > -1) {
		sh "rm -rf ./library"
	}
}

/**
* Run Unit Tests and generate Code Coverage Reports
 */
def generateCoverageReports() {
  def repo_name = service_config['domain'] + "_" + service_config['service']
	
	if (service_config['runtime'].indexOf("nodejs") > -1) {
		sh "npm install"
		sh "npm run coverage"
	} else if (service_config['runtime'].indexOf("java") > -1) {
		sh "mvn verify > outputMvnVerify.log 2>&1"
	} else if (service_config['runtime'].indexOf("python") > -1) {
		// install requirements.txt in library folder, these python modules will be a part of deployment package
		sh "mkdir library"
		//Installing dependencies
		sh "pip install -r requirements.txt -t library"
		sh "touch library/__init__.py"
		if (service_config['runtime'] == 'python3.6' || service_config['runtime'] == 'python3.8') {
			// create virtual environment and install pytest
			sh """
			python3 -m venv virtualenv
			. virtualenv/bin/activate
			pip3 install pytest
			pip3 install coverage
			coverage run -m pytest --ignore=library
			coverage xml -i
			"""
		} else {
			// create virtual environment and install pytest
			sh """
			pip install virtualenv
			virtualenv venv
			. venv/bin/activate
			pip install pytest
			pip install coverage
			coverage run -m pytest --ignore=library
			coverage xml -i
			"""
		}
	}  else if (service_config['runtime'].indexOf("go") > -1 ) {
		// Installing dependencies using dep ensure
		// golang build scripts
		withEnv(["GOPATH=${env.WORKSPACE}"]) {
			File functionPath = new File("${env.WORKSPACE}/${repo_name}/functions/");
			if (functionPath.isDirectory()) {
				sh "mkdir -p $GOPATH/src"
				sh "rsync -a --exclude='.*' $GOPATH/" + repo_name + " $GOPATH/src"
				sh "cd $GOPATH/src/"+ repo_name+" && dep ensure"
				dirs = getFunctionPaths()
				def size = dirs.size()
				if( size > 0) {
					for(item in dirs) {
						def functionpath = "$GOPATH/src/${repo_name}/${item}"
						sh "env GOOS=linux GOARCH=amd64 go build -o ${item}/main ${functionpath}/main.go"
					}
					sh "cd $GOPATH/src/" + repo_name + " && go test ./... -short -coverprofile=cov.out"
					sh "cd $GOPATH/src/" + repo_name + " && go tool cover -func=cov.out"
					sh "cp $GOPATH/src/" + repo_name + "/cov.out ${env.WORKSPACE}/${repo_name}/cov.out"
				} else {
					error "No Functions Found..skipping build"
				}
			} else {
				sh "mkdir -p $GOPATH/src"
				sh "rsync -a --exclude='.*' $GOPATH/" + repo_name + " $GOPATH/src"
				sh "cd $GOPATH/src/"+ repo_name+" && dep ensure"
				sh "cd $GOPATH/src/"+ repo_name+" && env GOOS=linux GOARCH=amd64 go build -o $GOPATH/"+ repo_name+"/main  *.go"
				sh "cd $GOPATH/src/" + repo_name + " && go test -short -coverprofile=cov.out"
				sh "cd $GOPATH/src/" + repo_name + " && go tool cover -func=cov.out"
				sh "cp $GOPATH/src/" + repo_name + "/cov.out ${env.WORKSPACE}/${repo_name}/cov.out"
			}
		}
	} else if (service_config['framework'] && service_config['framework'].indexOf("angular") > -1) {
		sh """
		cd app/
		npm install
		npm run test -- --no-watch --code-coverage > outputTest.log 2>&1
		"""
	} else if (service_config['framework'] && service_config['framework'].indexOf("react") > -1) {
		sh """
		cd app/
		npm install
		npm test -- --coverage > outputTest.log 2>&1
		"""
	}
}

/** 
* Get list of paths for functions. They are expected in functions/ directory
*/
def getFunctionPaths() {
  sh "ls -al"
  def targetPaths = null
  try {
    targetPaths = sh(returnStdout: true, script: 'ls -d functions/*').split("\n")
    echo "completed : $targetPaths"
  } catch (ex) {
      echo "Error occured while getting the functions list. Please make sure that your functions are available in functions/ directory"
  }
  return targetPaths
}

/**
 * Configure and initiate code analyzer.
 */
def doAnalysis() {
	try {
		if (config_loader.CODE_QUALITY.SONAR.ENABLE_SONAR && config_loader.CODE_QUALITY.SONAR.ENABLE_SONAR == "true") {
			configureSonarProperties()
			if (config_loader.CODE_QUALITY.SONAR.ENABLE_VULNERABILITY_SCAN && config_loader.CODE_QUALITY.SONAR.ENABLE_VULNERABILITY_SCAN == "true") {
				runVulnerabilityScan()
			}
			runReport()
		}
	} catch (ex) {
		error "Sonar analysis failed: " + ex.getMessage()
	}
}

/**
Setup, configure and run dependency-check
*/
def runVulnerabilityScan() {
	// create dir if not exists
	sh "mkdir -p ${config_loader.CODE_QUALITY.SONAR.DEPENDENCY_CHECK_NIST_FILES_LOCATION}"

	def isDirEmpty_cl = "[ -z \"\$(find ${config_loader.CODE_QUALITY.SONAR.DEPENDENCY_CHECK_NIST_FILES_LOCATION} -maxdepth 1 -type f)\" ];"

	def downloadFiles_cl = " wget ${config_loader.CODE_QUALITY.SONAR.DEPENDENCY_CHECK_NIST_MIRROR_UTILITY} -q -O nist-data-mirror.jar && java -jar nist-data-mirror.jar nist_files && mv nist_files/* ${config_loader.CODE_QUALITY.SONAR.DEPENDENCY_CHECK_NIST_FILES_LOCATION}"
	sh "if $isDirEmpty_cl then $downloadFiles_cl; fi;"

	// run dependency check on the current dir
	sh "ls -al ${config_loader.CODE_QUALITY.SONAR.DEPENDENCY_CHECK_NIST_FILES_LOCATION}"

	g_dependency_check_properties["project"] = g_sonar_projectKey
	g_dependency_check_properties["scan"] = "."
	g_dependency_check_properties["exclude"] = "**/*.zip"
	g_dependency_check_properties["out"] = "."
	g_dependency_check_properties["format"] = "XML"
	g_dependency_check_properties["cveUrl12Modified"] = "file://${config_loader.CODE_QUALITY.SONAR.DEPENDENCY_CHECK_NIST_FILES_LOCATION}/nvdcve-Modified.xml"
	g_dependency_check_properties["cveUrl20Modified"] = "file://${config_loader.CODE_QUALITY.SONAR.DEPENDENCY_CHECK_NIST_FILES_LOCATION}/nvdcve-2.0-Modified.xml"
	g_dependency_check_properties["cveUrl12Base"] = "file://${config_loader.CODE_QUALITY.SONAR.DEPENDENCY_CHECK_NIST_FILES_LOCATION}/nvdcve-%d.xml"
	g_dependency_check_properties["cveUrl20Base"] = "file://${config_loader.CODE_QUALITY.SONAR.DEPENDENCY_CHECK_NIST_FILES_LOCATION}/nvdcve-2.0-%d.xml"
	g_dependency_check_properties["cveValidForHours"] = config_loader.CODE_QUALITY.SONAR.DEPENDENCY_CHECK_ELAPSED_HOURS_BEFORE_UPDATES

	def dependency_check_cl = "dependency-check.sh "

	for (item in g_dependency_check_properties) {
		dependency_check_cl += " --${item.key} ${item.value} "
	}

	sh dependency_check_cl
}

def cleanupCodeQualityReports(){
	withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: config_loader.CODE_QUALITY.SONAR.ADMIN_SONAR_CREDENTIAL_ID, passwordVariable: 'PWD', usernameVariable: 'UNAME']]) {
		try {
			def cleanupList = getSonarProject()
			if (cleanupList.size() > 0) {
				def cleanedupListStr = cleanupList.join(',');
				def url = "http://${config_loader.CODE_QUALITY.SONAR.HOST_NAME}/api/projects/bulk_delete?keys=${cleanedupListStr}"
				def response = sh(script: "curl -X POST  -IL --silent \
							--write-out %{http_code} --silent --output /dev/null  ${url} \
						-k -v -u  $UNAME:$PWD ", returnStdout: true).trim()

				echo "cleanupCodeQualityReports : $response"
				if (response == "200" || response == "204") {
					echo "Successfully cleaned the code quality reports from sonar."
				} else {
					error "error occured While deleting code quality reports"
				}
			} else {
				echo "No sonar reports found."
			}
		} catch (ex) {
			echo "error occured while deleting code quality reports: " + ex.getMessage()
		}
	}
}

def getSonarProject(){
	withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: config_loader.CODE_QUALITY.SONAR.ADMIN_SONAR_CREDENTIAL_ID, passwordVariable: 'PWD', usernameVariable: 'UNAME']]) {
		try {
			def project_key = getSonarProjectKey()
			def url = "http://${config_loader.CODE_QUALITY.SONAR.HOST_NAME}/api/projects/index?search=${project_key}"
			def response = sh(script: "curl -X POST \
						${url} \
						-k -v -u  $UNAME:$PWD  \
						-H \"Content-Type: application/x-www-form-urlencoded\" ", returnStdout: true).trim()

			def responseJSON = parseJson(response)
			def filtered = [];
			echo "getSonarProject : $responseJSON"
			if (responseJSON.size() > 0) {
				for (data in responseJSON) {
					filtered.push(data.k)
				}
			}
			echo "getSonarProject : $filtered"
			return filtered
		} catch (ex) {
			echo "error occured While fetching code quality reports from sonar : " + ex.getMessage()
		}
	}
}

@NonCPS
def parseJson(jsonString) {
	def nonLazyMap = new groovy.json.JsonSlurperClassic().parseText(jsonString)
	return nonLazyMap
}

/**
 * Jazz shebang that runs quietly and disable all console logs
 *
 */
def jazz_quiet_sh(cmd) {
	sh('#!/bin/sh -e\n' + cmd)
}

/**
 * Set projectKey
 * @return
 */

def setProjectKey(projectKey) {
	g_sonar_projectKey = projectKey

}

def setBranch(branch) {
	g_branch = branch
}

def setServiceConfig(serviceConfig){
	service_config = serviceConfig
}

def setConfigLoader(configLoader){
	config_loader = configLoader
}

/**
 * Set Username
 * @return
 */

def setUserName(login) {
	g_sonar_login = login
}

/**
 * Set Password
 * @return
 */

def setPassword(password) {
	g_sonar_password = password
}

return this;
