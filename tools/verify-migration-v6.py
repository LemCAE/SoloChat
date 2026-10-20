"""只操作取证副本，验证实际 v5 SQL 的追加迁移与导出 v6 schema；不访问手机。"""
import json
import pathlib
import re
import sqlite3
import sys
import tarfile

repo = pathlib.Path(__file__).resolve().parents[1]
archive_path = pathlib.Path(sys.argv[1]).resolve()
dest = archive_path.parent / 'migration-v6'
dest.mkdir(exist_ok=True)
with tarfile.open(archive_path) as archive:
    for member in archive.getmembers():
        if member.isfile() and member.name.startswith('databases/'):
            (dest / pathlib.Path(member.name).name).write_bytes(archive.extractfile(member).read())
db = sqlite3.connect(dest / 'solochat.db')
assert db.execute('pragma integrity_check').fetchone()[0] == 'ok'
assert db.execute('pragma user_version').fetchone()[0] == 5
tables = [r[0] for r in db.execute("select name from sqlite_master where type='table' and name not like 'sqlite_%'")]
old = {table: (db.execute(f'pragma table_info("{table}")').fetchall(), db.execute(f'select * from "{table}"').fetchall()) for table in tables}
source = (repo / 'app/src/main/kotlin/app/solochat/data/db/SchemaV6.kt').read_text(encoding='utf-8')
sql = re.findall(r'^    "([^"]+)"', source, flags=re.M)
assert len(sql) == 13
with db:
    for statement in sql:
        db.execute(statement)
schema = json.loads((repo / 'app/schemas/app.solochat.data.db.SoloChatDatabase/6.json').read_text())['database']
fresh = sqlite3.connect(':memory:')
for entity in schema['entities']:
    name = entity['tableName']
    fresh.execute(entity['createSql'].replace('${TABLE_NAME}', name))
    for index in entity['indices']:
        fresh.execute(index['createSql'].replace('${TABLE_NAME}', name))
    def columns(connection):
        # Room 对实体未声明默认值的列不核对 SQL 默认值；其余结构逐项核对。
        return sorted((r[1], r[2], r[3], r[5]) for r in connection.execute(f'pragma table_info("{name}")'))
    assert columns(db) == columns(fresh), name
    def indexes(connection):
        result = []
        for row in connection.execute(f'pragma index_list("{name}")'):
            if row[3] == 'pk':
                continue
            result.append((row[1], row[2], tuple(r[2] for r in connection.execute(f'pragma index_info("{row[1]}")'))))
        return sorted(result)
    assert indexes(db) == indexes(fresh), name
for table, (columns, rows) in old.items():
    projection = ','.join('"' + c[1] + '"' for c in columns)
    assert rows == db.execute(f'select {projection} from "{table}"').fetchall(), table
assert db.execute('pragma integrity_check').fetchone()[0] == 'ok'
report = {'from': 5, 'to': 6, 'tables_verified': len(schema['entities']),
          'original_rows_unchanged': {table: len(rows) for table, (_, rows) in old.items()},
          'integrity': 'ok'}
(archive_path.parent / 'migration-v6-result.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
print(json.dumps(report))
