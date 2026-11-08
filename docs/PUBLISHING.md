# CI and publishing

## Continuous integration

`CI` runs on pull requests, pushes to `main`, and manual dispatch. It uses
Temurin Java 21, caches Maven dependencies, and runs `mvn -B -ntp verify`.
This includes unit tests, source/Javadoc JAR generation, Javadoc warning checks,
and JaCoCo coverage. Test results and HTML/XML coverage reports are saved as a
GitHub Actions artifact for 14 days. No database services or external coverage
account are required.

The normal reactor includes `assert-sql-examples`, including real Hibernate queries
against an embedded in-memory H2 database. No external database service is needed.
That module is test-only: `maven.install.skip=true` skips its local installation,
and `maven.deploy.skip=true` excludes it from GitHub Packages,
GPG signing, and Central staging. The parent, core, and optional validation module
are published artifacts. Both library modules run in the normal CI reactor;
validation's dependencies are confined to its own artifact.

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
the parent POM, core module, and validation module to Central Portal.

Like SQM, `autoPublish` is disabled: the job waits for validation, then you finish
publication in [Central Portal](https://central.sonatype.com/publishing/deployments).
The generated deployment bundle is retained as a workflow artifact for seven days.
Central releases are immutable; use a new version for later changes.

Keep `main` on a snapshot version. Prepare release notes on a dedicated branch,
open a pull request, and merge only after required CI checks pass. For a release,
tag that merged commit and push only the release tag; never push commits directly
to `main` or bypass branch protection. The tag determines the release version even if the committed POM still
contains the snapshot version. No release is uploaded merely by opening a pull request.

For the first release, use `v0.1.0` and the notes in [releases/0.1.0.md](releases/0.1.0.md).
Create the GitHub release against that same tag. Confirm that the Central workflow
has validated the deployment, finish publishing in Central Portal, and verify that
both library artifacts resolve from Maven Central before announcing availability.

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
