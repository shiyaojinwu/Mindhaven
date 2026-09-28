#!/usr/bin/env python3
"""Test --check model/collection selection with stubbed dependencies; starts no services."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]

class LauncherConfigTest(unittest.TestCase):
    def check(self, config, expected, mode='--vector', native=False, code=0):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            shutil.copy(ROOT / 'start.sh', root / 'start.sh')
            (root / '.env').write_text(config)
            bin = root / 'bin'
            bin.mkdir()
            for name in ['npm', 'mvn', 'curl', 'docker', 'ollama']:
                (bin / name).write_text('#!/bin/sh\nexit 0\n')
            if native:
                (bin / 'qdrant').write_text('#!/bin/sh\nexit 0\n')
                # A native launch must work even when Docker is unavailable.
                (bin / 'docker').write_text('#!/bin/sh\nexit 127\n')
            (bin / 'lsof').write_text('#!/bin/sh\nexit 1\n')
            (bin / 'java').write_text('#!/bin/sh\necho \'openjdk version "21.0.1"\' >&2\n')
            (bin / 'node').write_text('''#!/usr/bin/env python3
import sys,os,hashlib
if '--version' in sys.argv: print('v22.12.0')
elif 'crypto' in sys.argv[-1]: print(hashlib.sha256(os.environ['EMBEDDING_MODEL'].encode()).hexdigest()[:16],end='')
''')
            for p in bin.iterdir():
                p.chmod(0o755)
            env = {k:v for k,v in os.environ.items() if k not in ['EMBEDDING_MODEL','QDRANT_COLLECTION','QDRANT_RUNTIME','DEEPSEEK_API_KEY','PORT']}
            env.update(PATH=str(bin)+os.pathsep+env['PATH'], JAVA_HOME=str(root))
            result = subprocess.run(['bash',str(root/'start.sh'),mode,'--check'],env=env,text=True,capture_output=True)
            self.assertEqual(result.returncode,code,result.stdout+result.stderr)
            self.assertIn(expected,result.stdout+result.stderr)
            self.assertFalse((root/'.runtime').exists())

    def test_default(self):
        self.check('', 'Embedding：embeddinggemma；Qdrant 集合：mindhaven_embeddinggemma_v1')
    def test_old_model(self):
        self.check('EMBEDDING_MODEL=bge-m3\n','Qdrant 集合：mindhaven_bge_m3_v1')
    def test_explicit_collection(self):
        self.check('EMBEDDING_MODEL=embeddinggemma\nQDRANT_COLLECTION=custom_v2\n','Qdrant 集合：custom_v2')
    def test_other_model(self):
        model='test/model:small'
        key=hashlib.sha256(model.encode()).hexdigest()[:16]
        self.check('EMBEDDING_MODEL='+model+'\n','Qdrant 集合：mindhaven_'+key+'_v1')
    def test_live(self):
        self.check('DEEPSEEK_API_KEY=fixture-only\n','检查通过：模式 live','--live')
    def test_demo(self):
        self.check('','检查通过：模式 demo','--demo')
    def test_ai_without_vector(self):
        self.check('DEEPSEEK_API_KEY=fixture-only\n','检查通过：模式 ai','--ai')
    def test_native_without_docker(self):
        self.check('', 'Qdrant 运行方式：native', native=True)
    def test_invalid_runtime(self):
        self.check('QDRANT_RUNTIME=unknown\n', 'QDRANT_RUNTIME 只支持', code=1)

if __name__=='__main__': unittest.main()
