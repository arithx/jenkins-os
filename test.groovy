#!groovy

properties([
    parameters([
        string(name: 'VERSION',
               defaultValue: '',
               description: 'Which OS version to sync'),
        choice(name: 'CHANNEL',
               choices: "alpha\nbeta\nstable",
               description: 'Which release channel to use'),
        string(name: 'BASE_VERSION',
               defaultValue: '',
               description: '''Copy documentation from this release if \
given, otherwise sync documentation from Git'''),
        choice(name: 'LATEST',
               choices: "auto\nyes\nno",
               description: '''Whether to set the "latest" directory \
to this version, where "auto" enables it only for "alpha" releases'''),
    ])
])

/* Generate all of the actual required strings from the above values.  */
def base = params.BASE_VERSION
def channel = params.CHANNEL
def version = params.VERSION
def latest = [auto: channel == 'alpha'].withDefault{it == 'yes'}[params.LATEST]

if ((channel != 'alpha' || version =~ '\\d+\\.[1-9]\\d*\\.\\d+') && base == '') {
    currentBuild.result = 'FAILURE'
    return
}
