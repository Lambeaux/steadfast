# Steadfast
Tooling to help refactor large OSGi applications running on Apache Karaf. 

### Usage

- Install the `feature-try-install` bundle into Karaf.
- Add the feature repository containing the problematic feature to be installed.
- Run `feature:tryinstall <NAME>` to attempt resolution.
- Inspect the exports of the `Dependency Provider` bundle that was generated and installed into the container.

If the command succeeded, those exports should list all packages necessary to resolve the feature.
Update the feature file accordingly.

**Important**: Only missing packages will be resolved. Not services or capabilities. 

Do not use `feature:tryinstall` for setting up Karaf containers that will be used in production. 
This is a development tool to make refactoring features less tedious. 