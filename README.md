# Modern NotTooExpensive

Removes "Too expensive" from anvils.

## Configuration

By default, the plugin keeps its existing behavior and may replace high-cost
anvil results.

Set `ui-only-mode: true` in `config.yml` when another plugin already manages
anvil costs, limits, or enchant conflicts. In UI-only mode, this plugin does not
calculate costs, set results, or change repair costs. It only spoofs the client
display so high repair costs can be shown instead of "Too Expensive!".

## Building

Build with the Gradle wrapper:

```bash
./gradlew clean build
```

On Windows:

```bat
gradlew.bat clean build
```

The jar is written to `build/libs/`.

To copy the jar to a local test server, pass a plugins directory explicitly:

```bash
./gradlew build copyJarToLocalServer -PlocalServerPluginsDir=<plugins-dir>
```

On Windows:

```bat
gradlew.bat build copyJarToLocalServer -PlocalServerPluginsDir=C:\path\to\server\plugins
```
