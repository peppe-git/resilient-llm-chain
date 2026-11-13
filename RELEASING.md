# Releasing

Releases go to Maven Central through the Central Publisher Portal. The `release` workflow builds, signs
and uploads them. Nothing becomes public until a maintainer presses **Publish** in the portal: the
build uses `autoPublish=false`.

## One-time setup

1. **Namespace.** The groupId `io.github.peppe-git` must be verified in the
   [Central Publisher Portal](https://central.sonatype.com) for the GitHub account `peppe-git`.
2. **User token.** In the portal, generate a user token: a username and a password.
3. **Signing key.**
   - Create a GPG key for releases and publish its public part to a keyserver, for example
     `keys.openpgp.org`.
   - Export the secret key ASCII-armored with `gpg --armor --export-secret-keys <key id>`.
4. **Repository secrets**, under Settings → Secrets and variables → Actions:

   | Secret | Value |
   |---|---|
   | `MAVEN_CENTRAL_USERNAME` | token username |
   | `MAVEN_CENTRAL_PASSWORD` | token password |
   | `GPG_PRIVATE_KEY` | the ASCII-armored secret key |
   | `GPG_PASSPHRASE` | its passphrase |

5. **Recommended protections.**
   - A tag ruleset that restricts who can create `v*` tags.
   - Branch protection on `main` that requires the `build` check.

## Dry run, locally

```bash
./mvnw -Prelease -Dgpg.skip -DskipTests verify
```

It must exit with 0 and leave a `-sources.jar` and a `-javadoc.jar` next to each module's jar.

## Cutting a release

Replace `X.Y.Z` with the version.

1. **Start from a green `main`.**
2. **Changelog.** In `CHANGELOG.md`, rename `[Unreleased]` to `[X.Y.Z] - <date>` and open a new, empty
   `[Unreleased]` section above it.
3. **Set the release version** in every POM:

   ```bash
   ./mvnw versions:set -DnewVersion=X.Y.Z -DgenerateBackupPoms=false
   ```

4. **Commit and tag**, then push both:

   ```bash
   git commit -am "chore(release): X.Y.Z"
   git tag -a vX.Y.Z -m "X.Y.Z"
   git push origin main vX.Y.Z
   ```

5. **Workflow.** The tag starts the `release` workflow:
   - It fails at once if the tag and the POM version differ, or if the version is a `-SNAPSHOT`.
   - It then runs the scrub check and the full build with tests.
   - It signs every artifact and uploads one bundle to the portal.

   You can also start it by hand ("Run workflow") on an existing tag.
6. **Publish in the portal.** In the [Central Publisher Portal](https://central.sonatype.com/publishing/deployments),
   open the deployment `resilient-llm-chain X.Y.Z`. It must be *Validated* and list four components:
   - the parent POM;
   - `resilient-llm-chain-core`;
   - `resilient-llm-chain-redis`;
   - `resilient-llm-chain-openai-compatible`.

   Each one has its jar, sources jar, javadoc jar, POM and `.asc` signatures.

   Press **Publish**. The artifacts reach the repository within about half an hour; search indexes
   follow later.
7. **GitHub release.** Create a release from the tag, with the changelog section as notes.
8. **Back to a snapshot.** Move to the next development version:

   ```bash
   ./mvnw versions:set -DnewVersion=X.Y.(Z+1)-SNAPSHOT -DgenerateBackupPoms=false
   git commit -am "chore: start X.Y.(Z+1)-SNAPSHOT"
   git push origin main
   ```

## When something goes wrong

- **Validation fails in the portal.** Drop the deployment there, fix the cause, then delete the tag
  locally and remotely and tag again. Nothing was published.
- **A version was published by mistake.** Releases on Maven Central are immutable. Fix forward with
  a new patch version.
- **The workflow fails before the upload.** For example, a test failure. Nothing reached the portal:
  fix, move the tag, and run again.
