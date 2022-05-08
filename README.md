# The Mod Index Validation

A GitHub action that validates all entries in the-mod-index. Will fail if any entries have been found to not be up to the given spec.

### Usage:

```yaml
steps:
  - name: Do the checking of index and manifest files
    uses: reviversmc/the-mod-index-validation@v1
    with: 
      repoUrl: https://raw.githubusercontent.com/ReviversMC/the-mod-index/v1
```