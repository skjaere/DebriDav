# Changelog

## [0.12.0](https://github.com/skjaere/DebriDav/compare/v0.11.0...v0.12.0) (2026-03-02)


### Features

* add health check and repair pipeline ([7e22d54](https://github.com/skjaere/DebriDav/commit/7e22d54d765c4cfb6cf0615831a353c6e0ec4bda))
* add NZB import and streaming support ([8755dec](https://github.com/skjaere/DebriDav/commit/8755dec61f57d1221b1e3d52b0a422e954332ab0))
* support raw and nested NZB archives ([420c5e9](https://github.com/skjaere/DebriDav/commit/420c5e972f8c87b040fab21b4276c5459efc882d))


### Bug Fixes

* add missing v prefix to mock-nntp-server version tag ([478df6a](https://github.com/skjaere/DebriDav/commit/478df6a149a77e32d163f3e0b0b389f0ce020986))
* add sentry-logback dependency for error capture ([04969ae](https://github.com/skjaere/DebriDav/commit/04969ae9af352ccfd3b0b6ad53bf1254ce6b5451))
* change postgres service type to LoadBalancer for external access ([e86640b](https://github.com/skjaere/DebriDav/commit/e86640b2c8ad6ebd28e1d902e21abd39837f4051))
* **deps:** update dependency com.dampcake:bencode to v1.4.2 ([32e3df9](https://github.com/skjaere/DebriDav/commit/32e3df9b3e5ee59ab9c9a0b64538284cc676d370))
* **deps:** update dependency com.github.multiformats:java-multibase to v1.3.0 ([#51](https://github.com/skjaere/DebriDav/issues/51)) ([f9e701c](https://github.com/skjaere/DebriDav/commit/f9e701c8a600b4d06c07dcf5ea57128f61a36cd7))
* **deps:** update dependency com.google.guava:guava to v33.5.0-jre ([#52](https://github.com/skjaere/DebriDav/issues/52)) ([d5264ba](https://github.com/skjaere/DebriDav/commit/d5264ba12cd5e186ec583f02a898e774c07b10da))
* **deps:** update dependency io.milton:milton-server-ce to v4.1.0.2601 ([b3ecb45](https://github.com/skjaere/DebriDav/commit/b3ecb4597b34f3ce14e3a3d7bbcc382b695cff9c))
* **deps:** update dependency net.logstash.logback:logstash-logback-encoder to v7.4 ([#55](https://github.com/skjaere/DebriDav/issues/55)) ([e370624](https://github.com/skjaere/DebriDav/commit/e370624af395ed86c874cb98d282bb6a016b1c4c))
* **deps:** update dependency org.apache.httpcomponents.client5:httpclient5 to v5.6 ([#56](https://github.com/skjaere/DebriDav/issues/56)) ([f7fbb7f](https://github.com/skjaere/DebriDav/commit/f7fbb7f7d568e73381329d8f96373291807d3268))
* **deps:** update dependency org.jetbrains.kotlinx:kotlinx-serialization-json-jvm to v1.10.0 ([93a6e9e](https://github.com/skjaere/DebriDav/commit/93a6e9eaea0cf78ca0de674d7da5071309706209))
* **deps:** update dependency org.mock-server:mockserver-netty-no-dependencies to v5.15.0 ([#59](https://github.com/skjaere/DebriDav/issues/59)) ([325f540](https://github.com/skjaere/DebriDav/commit/325f540551fdde014c287c44937d856c3ada8050))
* **deps:** update dependency org.mockito.kotlin:mockito-kotlin to v5.4.0 ([#60](https://github.com/skjaere/DebriDav/issues/60)) ([aff2803](https://github.com/skjaere/DebriDav/commit/aff28036fccbdde191ee598a69a8fa416a426387))
* **deps:** update kotlin monorepo to v2.3.10 ([ebc27ba](https://github.com/skjaere/DebriDav/commit/ebc27ba29ae34f0c29256c06d8fad980dff4eb89))
* **deps:** update kotlinx-coroutines monorepo to v1.10.2 ([20c0590](https://github.com/skjaere/DebriDav/commit/20c0590c2caea3e04ec67c8791a59dd10eb96ebc))
* **deps:** update kotlinx-coroutines monorepo to v1.10.2 ([2e9cd2d](https://github.com/skjaere/DebriDav/commit/2e9cd2d44191bd917c253656fedd41516ca01bd5))
* **deps:** update ktor monorepo to v3.5.0-eap-1584 ([#61](https://github.com/skjaere/DebriDav/issues/61)) ([ab6b809](https://github.com/skjaere/DebriDav/commit/ab6b8099ff4fff6659861dfde9e223be815709b6))
* **deps:** update mockk to v1.14.9 ([#62](https://github.com/skjaere/DebriDav/issues/62)) ([ee2561f](https://github.com/skjaere/DebriDav/commit/ee2561fe3e29027f50aeeca9f0087397fef3c0c8))
* **deps:** update sentry to v8.33.0 ([#54](https://github.com/skjaere/DebriDav/issues/54)) ([08a3cbb](https://github.com/skjaere/DebriDav/commit/08a3cbb6a63855d3cb8c71c4cac544689bd0fa4b))
* **deps:** update spring boot to v4.0.3 ([4ada404](https://github.com/skjaere/DebriDav/commit/4ada404c3d6d9a00b0b8b44bd9074902ce279c0c))
* improving error handling for Real-Debrid ([f127c53](https://github.com/skjaere/DebriDav/commit/f127c5324aae916b681ecea6ea374b2fc3551ba1))
* improving error logging for debrid errors ([391f0c5](https://github.com/skjaere/DebriDav/commit/391f0c52f3183c8c1ba3205baaa132f21c39b46c))
* NZB streaming bug fixes ([32c682f](https://github.com/skjaere/DebriDav/commit/32c682f220a6507e9330e4c58b3f28e0a8b6dd79))
* replacing usage of deprecated StringUtils.removeEnd with Strings.CS.removeEnd ([21acf7e](https://github.com/skjaere/DebriDav/commit/21acf7e9c1a20a2ab975a82186661a11baa754d1))
* url encoding magnets for Real Debrid ([7c6f4ea](https://github.com/skjaere/DebriDav/commit/7c6f4ea08953d526e9f1b8d49238fd7c1a46f58f))

## [0.11.0](https://github.com/skjaere/DebriDav/compare/0.10.1...v0.11.0) (2026-02-18)


### Features

* add authentication support for WebDAV and update Milton to 4.0.5.2500 ([8858531](https://github.com/skjaere/DebriDav/commit/885853135db2b92987ac8a327ac9c09003daf9ca))
* **docker:** Pinning Postgres image to version 17 ([0f8c687](https://github.com/skjaere/DebriDav/commit/0f8c68719627c8b4190c7f25d96ebec705166940))
* removing byte chunk cache ([0ce36e1](https://github.com/skjaere/DebriDav/commit/0ce36e1bd265f75a04a14dfd56da974a7a1bdeee))


### Bug Fixes

* fix:  ([0e18986](https://github.com/skjaere/DebriDav/commit/0e189867f051dff5e4b3b86069f17c79f7b82f6d))
* adding client tag to streaming metrics ([8ee3ee3](https://github.com/skjaere/DebriDav/commit/8ee3ee303dce8d842adb4e33afe60925d5b35905))
* adding client tag to streaming metrics ([a3c1038](https://github.com/skjaere/DebriDav/commit/a3c1038e483e7e0e4de45d5358750f5ab97cf9ae))
* adding database migration for removing cache tables ([bc4c797](https://github.com/skjaere/DebriDav/commit/bc4c797ef0f08245b24f51981bbe958406361771))
* adding missing file ([3df91f0](https://github.com/skjaere/DebriDav/commit/3df91f09501da7f91bcd5719d082745beba0283d))
* bumping ktor to 3.4.0 ([aeadb6c](https://github.com/skjaere/DebriDav/commit/aeadb6cb03f7acd8526bea2eec22af0d9bd546c3))
* fixing database migration ([c7b1eac](https://github.com/skjaere/DebriDav/commit/c7b1eace2a53177d50a2e8d5ee6a27c8e87fcf25))
* fixing detekt errors ([73aa1fd](https://github.com/skjaere/DebriDav/commit/73aa1fd496621fb32ef729ede0447365041ddc07))
* fixing detekt errors ([948da1e](https://github.com/skjaere/DebriDav/commit/948da1eaa5321bfa6567288535a0542ecdc3e0c8))
* migrating to Spring Boot 4 ([941e6a3](https://github.com/skjaere/DebriDav/commit/941e6a362b8d5b6173c6664282a1c3ceb998b77e))
* **prowlarr:** Adjust Torrentio URL split argument ([4073c08](https://github.com/skjaere/DebriDav/commit/4073c08f011e7c0ad79e94debbeb65cd074cb60c))
* refactoring StreamingService ([8e0bea3](https://github.com/skjaere/DebriDav/commit/8e0bea346b4fecadc9e87fe7fc1c4d9aa6a1bba4))
* refactoring StreamingService ([2ae03b0](https://github.com/skjaere/DebriDav/commit/2ae03b0398123fa0323faa2db2d4f6b732f7a894))
* refactoring StreamingService.kt ([8452837](https://github.com/skjaere/DebriDav/commit/8452837138c4bda6d988525a58e7b8239763fd9e))
* removing logging ([aa538a8](https://github.com/skjaere/DebriDav/commit/aa538a844821f17ad643ab466f1e4f4d286e17e9))
