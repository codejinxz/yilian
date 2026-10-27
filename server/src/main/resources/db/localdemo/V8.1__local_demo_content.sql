-- 仅本地演示数据（与 web/src/mocks/db.ts 演示种子同源）。
-- 本文件位于 db/localdemo，只有当启动参数显式包含该 Flyway location 时才会执行，例如：
--   --spring.flyway.locations=classpath:db/migration,classpath:db/localdemo
-- prod 与默认 local 启动都不会加载；演示库可随时清库重放（演示数据可重置，DoD）。
-- 用途：游客「真实后端可用」模式下匿名拉取公开题库（GET /api/banks/**），本地需有已发布内容。

INSERT INTO sentence_banks (id, owner_id, name, description, visibility, status, version, published_at)
VALUES (1, NULL, '演示题库 · 日常', '本地演示用公开题库；与前端 mock 种子同源，供游客真实联调模式使用。',
        'PUBLIC', 'PUBLISHED', 8, UTC_TIMESTAMP(6));

INSERT INTO sentences (id, bank_id, external_key, status, version, fingerprint) VALUES
('01J9Z6K2M8Q4R7T1V3W5X7Y9A0', 1, 'demo-001', 'PUBLISHED', 1, SHA2('yilian-demo-001', 256)),
('01J9Z6K2M8Q4R7T1V3W5X7Y9A1', 1, 'demo-002', 'PUBLISHED', 2, SHA2('yilian-demo-002', 256)),
('01J9Z6K2M8Q4R7T1V3W5X7Y9A2', 1, 'demo-003', 'PUBLISHED', 3, SHA2('yilian-demo-003', 256)),
('01J9Z6K2M8Q4R7T1V3W5X7Y9A3', 1, 'demo-004', 'PUBLISHED', 4, SHA2('yilian-demo-004', 256)),
('01J9Z6K2M8Q4R7T1V3W5X7Y9A4', 1, 'demo-005', 'PUBLISHED', 5, SHA2('yilian-demo-005', 256)),
('01J9Z6K2M8Q4R7T1V3W5X7Y9A5', 1, 'demo-006', 'PUBLISHED', 6, SHA2('yilian-demo-006', 256)),
('01J9Z6K2M8Q4R7T1V3W5X7Y9A6', 1, 'demo-007', 'PUBLISHED', 7, SHA2('yilian-demo-007', 256)),
('01J9Z6K2M8Q4R7T1V3W5X7Y9A7', 1, 'demo-008', 'PUBLISHED', 8, SHA2('yilian-demo-008', 256));

INSERT INTO sentence_versions (sentence_id, bank_id, version_no, zh, ref, note, chunks, blank, tags, level, source, license, content_hash) VALUES
('01J9Z6K2M8Q4R7T1V3W5X7Y9A0', 1, 1,
 '他越来越重视这件事。', 'He is paying more and more attention to this.',
 '「越来越」可用 more and more 或更书面的 increasingly；「重视」后接 attention 时用 pay attention to，不能加 s。',
 '[{"index":0,"zh":"他","answers":["He"],"tag":"代词","note":null},{"index":1,"zh":"越来越","answers":["more and more","increasingly"],"tag":"副词结构","note":"increasingly 更书面"},{"index":2,"zh":"重视","answers":["pay attention to","attach importance to"],"tag":"搭配","note":"attach importance to 更正式"},{"index":3,"zh":"这件事","answers":["this","this matter"],"tag":"代词","note":null}]',
 '[1,2]', '["副词结构","搭配"]', 'TEM8', 'local-demo', 'demo', SHA2('yilian-demo-v-001', 256)),
('01J9Z6K2M8Q4R7T1V3W5X7Y9A1', 1, 1,
 '这项政策对中小企业影响最大。', 'This policy has the greatest impact on small and medium-sized enterprises.',
 '「影响最大」用 have the greatest impact on；small and medium-sized enterprises 是固定说法，medium 用单数。',
 '[{"index":0,"zh":"这项政策","answers":["This policy"],"tag":"名词短语","note":null},{"index":1,"zh":"影响最大","answers":["has the greatest impact"],"tag":"搭配","note":null},{"index":2,"zh":"对中小企业","answers":["on small and medium-sized enterprises","on SMEs"],"tag":"介词结构","note":"口语可用 SMEs，正式写作建议写全"}]',
 '[1,2]', '["搭配","介词结构"]', 'TEM8', 'local-demo', 'demo', SHA2('yilian-demo-v-002', 256)),
('01J9Z6K2M8Q4R7T1V3W5X7Y9A2', 1, 1,
 '我们应当把有限的时间用在最重要的事情上。', 'We should spend our limited time on the most important things.',
 'spend time on something 的介词是 on；「最重要」用 the most important，注意定冠词。',
 '[{"index":0,"zh":"我们","answers":["We"],"tag":"代词","note":null},{"index":1,"zh":"应当花费","answers":["should spend"],"tag":"情态动词","note":null},{"index":2,"zh":"有限的时间","answers":["our limited time"],"tag":"名词短语","note":null},{"index":3,"zh":"在最重要的事情上","answers":["on the most important things"],"tag":"介词结构","note":null}]',
 '[1,3]', '["情态动词","介词结构"]', 'CATTI', 'local-demo', 'demo', SHA2('yilian-demo-v-003', 256)),
('01J9Z6K2M8Q4R7T1V3W5X7Y9A3', 1, 1,
 '尽管困难重重，她仍然坚持自己的观点。', 'Despite the difficulties, she still stuck to her own point of view.',
 'despite 后接名词，不接句子；stick to 的过去式是 stuck，不要写成 sticked。',
 '[{"index":0,"zh":"尽管困难重重","answers":["Despite the difficulties"],"tag":"让步结构","note":null},{"index":1,"zh":"她仍然","answers":["she still"],"tag":"副词","note":null},{"index":2,"zh":"坚持","answers":["stuck to"],"tag":"动词短语","note":null},{"index":3,"zh":"自己的观点","answers":["her own point of view"],"tag":"名词短语","note":null}]',
 '[0,2]', '["让步结构","动词短语"]', 'TEM8', 'local-demo', 'demo', SHA2('yilian-demo-v-004', 256)),
('01J9Z6K2M8Q4R7T1V3W5X7Y9A4', 1, 1,
 '这本书值得一读。', 'This book is worth reading.',
 'be worth doing 是固定搭配，worth 后接动名词，不接不定式。',
 '[{"index":0,"zh":"这本书","answers":["This book"],"tag":"名词短语","note":null},{"index":1,"zh":"值得","answers":["is worth"],"tag":"搭配","note":null},{"index":2,"zh":"一读","answers":["reading"],"tag":"非谓语","note":null}]',
 '[1,2]', '["搭配","非谓语"]', 'CET6', 'local-demo', 'demo', SHA2('yilian-demo-v-005', 256)),
('01J9Z6K2M8Q4R7T1V3W5X7Y9A5', 1, 1,
 '会议被推迟到下周三。', 'The meeting has been postponed until next Wednesday.',
 '现在完成时的被动语态是 has been postponed；推迟到某时用 until，不用 to。',
 '[{"index":0,"zh":"会议","answers":["The meeting"],"tag":"名词短语","note":null},{"index":1,"zh":"被推迟","answers":["has been postponed"],"tag":"被动语态","note":null},{"index":2,"zh":"到下周三","answers":["until next Wednesday"],"tag":"介词结构","note":null}]',
 '[1,2]', '["被动语态","介词结构"]', 'CET6', 'local-demo', 'demo', SHA2('yilian-demo-v-006', 256)),
('01J9Z6K2M8Q4R7T1V3W5X7Y9A6', 1, 1,
 '他因为迟到而道歉。', 'He apologized for being late.',
 'apologize for doing 是固定搭配；for 后接动名词 being，不接不定式。',
 '[{"index":0,"zh":"他","answers":["He"],"tag":"代词","note":null},{"index":1,"zh":"道歉","answers":["apologized"],"tag":"动词搭配","note":null},{"index":2,"zh":"因为迟到","answers":["for being late"],"tag":"动名词","note":null}]',
 '[1,2]', '["动词搭配","动名词"]', 'CET6', 'local-demo', 'demo', SHA2('yilian-demo-v-007', 256)),
('01J9Z6K2M8Q4R7T1V3W5X7Y9A7', 1, 1,
 '越来越多的人开始关注环境保护。', 'More and more people are beginning to pay attention to environmental protection.',
 '「越来越多」用 more and more；begin to do 与 begin doing 均可，但本句用不定式更自然。',
 '[{"index":0,"zh":"越来越多的人","answers":["More and more people"],"tag":"副词结构","note":null},{"index":1,"zh":"开始","answers":["are beginning"],"tag":"动词搭配","note":null},{"index":2,"zh":"关注","answers":["to pay attention to"],"tag":"搭配","note":null},{"index":3,"zh":"环境保护","answers":["environmental protection"],"tag":"名词短语","note":null}]',
 '[1,2,3]', '["副词结构","搭配","名词短语"]', 'TEM8', 'local-demo', 'demo', SHA2('yilian-demo-v-008', 256));

-- sentences.current_version_id 与 sentence_versions 互相外键，插入完成后回填指针。
UPDATE sentences s
JOIN sentence_versions v ON v.sentence_id = s.id AND v.version_no = 1
SET s.current_version_id = v.id
WHERE s.bank_id = 1;
