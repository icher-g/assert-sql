# CI and publishing

## Continuous integration

`CI` runs on pull requests, pushes to `main`, and manual dispatch. It uses
Temurin Java 21, caches Maven dependencies, and runs `mvn -B -ntp verify`.
This includes unit tests, source/Javadoc JAR generation, Javadoc warning checks,
and JaCoCo coverage. Test results and HTML/XML coverage reports are saved as a
GitHub Actions artifact for 14 days. No database services or external coverage
account are required.

## Snapshots

`Publish Maven` verifies and deploys the committed `-SNAPSHOT` version to
[GitHub Packages](https://github.com/icher-g/assert-sql/packages) on pushes to
`main`. It can also be dispatched manually on `main`. It uses the built-in
`GITHUB_TOKEN` with `packages: write`; no personal token is needed by the workflow.
Consumers of GitHub Maven packages still need credentials when downloading.

## Maven Central releases

Release tags such as `v0.1.0` or `v0.2.0-rc.1` run the Central job. You can also
manually dispatch the workflow with an existing release tag selected as its ref.
Other manually selected branches do not publish. Snapshot tags are rejected.

Before the first release, configure these GitHub Actions repository secrets,
using the same names as SQM:

| Secret                   | Value                                           |
|--------------------------|-------------------------------------------------|
| `CENTRAL_TOKEN_USERNAME` | Central Portal user-token username              |
| `CENTRAL_TOKEN_PASSWORD` | Central Portal user-token password              |
| `MAVEN_GPG_PRIVATE_KEY`  | ASCII-armored private signing key               |
| `MAVEN_GPG_PASSPHRASE`   | Signing key passphrase, if the key is protected |

The account must be authorized for the verified `io.github.icher-g` namespace.
The signing public key must be discoverable by Central's signature validation.
Secrets configured only in the SQM repository are not automatically available
here; configure them for this repository or grant access to organization secrets.

The workflow derives the version from the tag and uses the pinned Maven Versions
plugin to update parent and module POMs **only in the runner checkout**. There is
no version commit or push back to GitHub. It then runs tests, builds source and
Javadoc JARs, signs artifacts using the Maven GPG plugin's BC signer, and uploads
both the parent POM and core module to Central Portal.

Like SQM, `autoPublish` is disabled: the job waits for validation, then you finish
publication in [Central Portal](https://central.sonatype.com/publishing/deployments).
The generated deployment bundle is retained as a workflow artifact for seven days.
Central releases are immutable; use a new version for later changes.

Keep `main` on a snapshot version. For a release, tag the intended commit and push
that tag; the tag determines the release version even if the committed POM still
contains the snapshot version. No release is uploaded merely by opening a pull request.

## Local packaging checks

```shell
mvn verify
```

For a signed bundle without uploading, use a release-version working copy and
configure `MAVEN_GPG_KEY` (ASCII-armored private key) and, if needed,
`MAVEN_GPG_PASSPHRASE` in the environment:

```shell
mvn -Pcentral-release -Dcentral.skipUpload=true deploy
```

The bundle is written to `target/central-publishing/central-bundle.zip`.
Normal development builds need no publishing credentials or signing key.
