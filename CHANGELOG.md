# Changelog


## 0.2.0

* Improvements:
  * Add ability to trigger downstream pipelines
  * Support LambdaCD 0.14.0 and Clojure 1.9
* API changes: 
  * Bump default LambdaCD dependency to 0.13.5
  * The way to provide pipeline-IDs changed: You now configure this in the pipeline-config and just pass a list of 
    pipelines to `initialize-value-stream`. Passing a map into `initialize-value-stream` is now deprecated, will be
    removed in subsequent releases and is not supported in newer features (e.g. downstream triggers). 
    See README.md for details. 

## 0.1.0

* Initial Release
