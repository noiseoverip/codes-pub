// References https://support.cloudbees.com/hc/en-us/articles/360030526992-How-to-manage-Credentials-via-the-REST-API

userToken = "YOUR_USER_NAME:TOKEN" // get token in https://JENKINS/user/YOUR_USER_NAME/configure
targetJenkinsHost = "https://YOUR_JENKINS_HOST"
// Path to post credentials to. Example below is to copy credentials to global credential store. To create creds in
// "folder" MYFOLDER: job/MYFOLDER/credentials/store/folder/domain/_/createCredentials
targetJenkinsPath = "credentials/store/system/domain/_/createCredentials"

// List of credentials to tranfer. To obtain it, login to Jenkins and then open URL:
// https://YOUR_JENKINS_HOSTNAME/credentials/store/system/domain/_/api/json?tree=credentials[id,typeName,description]
// Then simply COPY paste the output into variable below and modify if needed
credentials = """
"""

// List if secrets ids to skip
skipSecretIds = ["id1","id2"]

static def secretUsernamePassword(secretId, username, password, description) {
    return """
<com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl>
<scope>GLOBAL</scope>
<id>${secretId}</id>
<description>${description}</description>
<username>${username}</username>
<password>${password}</password>
</com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl>
    """
}

static def secretUserNameAndPrivateKey(secretId, username, passphrase, key, description) {
    return """
<com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey plugin="ssh-credentials@1.19">
<scope>GLOBAL</scope>
<id>${secretId}</id>
<description>${description}</description>
<username>${username}</username>
<usernameSecret>true</usernameSecret>
<passphrase>${passphrase}</passphrase>
<privateKeySource class="com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey\$DirectEntryPrivateKeySource">
<privateKey>${key}</privateKey>
</privateKeySource>
</com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey>
    """
}

static def secretText(secretId, secretText, description) {
    return """
<org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl plugin="plain-credentials@1.7">
<scope>GLOBAL</scope>
<id>${secretId}</id>
<description>${description}</description>
<secret>${secretText}</secret>
</org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl>
    """
}

static def secretFile(secretId, fileName, fileBytes, description) {
    return """
<org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl plugin="plain-credentials@1.7">
<scope>GLOBAL</scope>
<id>${secretId}</id>
<description>${description}</description>
<fileName>${fileName}</fileName>
<secretBytes>${fileBytes}
</secretBytes>
</org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl>
    """
}

def postToJenkins(def pipeline, String token=null, String path, def json=null) {
    pipeline.writeFile(file: "data", text: json)
    pipeline.sh(script: """#!/bin/sh +x
            curl -L -s POST\
                --header 'Content-Type: application/xml'\
                --user ${token}\
                --data @data\
                ${targetJenkinsHost}/${path} \
            && rm data
        """)
}

void runScript() {

    def creds = this.readJSON(text: credentials).credentials

    for (c in creds) {
        if (c.id in skipSecretIds) {
            continue
        }
        switch (c.typeName) {
            case "Secret file":
                echo("Copying [${c.typeName}] id:${c.id} type:${c.typeName}")

                withCredentials([file(credentialsId: c.id, variable: 'FILE_NAME')]) {
                    def fileContents  = readFile(file: env.FILE_NAME)
                    def fileName = env.FILE_NAME.substring(env.FILE_NAME.lastIndexOf('/') + 1)
                    postToJenkins(this, userToken, targetJenkinsPath,
                            secretFile(c.id, fileName, fileContents, c.description))
                }

                return
                break
            case "Secret text":
                echo("Copying [${c.typeName}] id:${c.id} type:${c.typeName}")

                withCredentials([string(credentialsId: c.id, variable: 'SECRET_TEXT')]) {
                    postToJenkins(this, userToken, targetJenkinsPath,
                            secretText(c.id, env.SECRET_TEXT, c.description))
                }
                break
            case "SSH Username with private key":
                echo("Copying [${c.typeName}] id:${c.id} type:${c.typeName}")

                withCredentials([sshUserPrivateKey(credentialsId: c.id, keyFileVariable: 'KEY_FILE', passphraseVariable: 'PASSPHRASE', usernameVariable: 'USERNAME')]) {
                    def key  = readFile(file: env.KEY_FILE)
                    postToJenkins(this, userToken, targetJenkinsPath,
                            secretUserNameAndPrivateKey(c.id, env.USERNAME, env.PASSPHRASE, key, c.description))
                }
                break
            case "Username with password":
                echo("Copying [${c.typeName}] id:${c.id} type:${c.typeName}")

                withCredentials([
                        usernamePassword(credentialsId: c.id,
                                usernameVariable: 'USERNAME',
                                passwordVariable: 'PASSWORD')]) {
                    postToJenkins(this, userToken, targetJenkinsPath,
                            secretUsernamePassword(c.id, env.USERNAME, env.PASSWORD, c.description))
                }
                break
        }
    }
}

node () {
    try {
        runScript()
    } catch(Exception e) {
        error(e.toString())
    } finally {
        cleanWs()
    }
}
