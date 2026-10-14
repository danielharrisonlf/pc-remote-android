const { getDefaultConfig, mergeConfig } = require('@react-native/metro-config');
const fs = require('fs');

/**
 * Metro configuration
 * https://reactnative.dev/docs/metro
 *
 * @type {import('@react-native/metro-config').MetroConfig}
 */
// Windows builds may use a short `subst` drive for Ninja's path limit. Metro
// must watch the canonical source path returned by Node's resolver.
const sourceRoot = fs.realpathSync.native(__dirname);
const config = {projectRoot: sourceRoot, watchFolders: [__dirname], maxWorkers: 2};

module.exports = mergeConfig(getDefaultConfig(sourceRoot), config);
