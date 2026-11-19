"""Regression checks for the source package privacy filter."""
from pathlib import Path
import sys
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parent))
from privacy_scan import findings

class PublicationPrivacyTests(unittest.TestCase):
    def test_absolute_and_escaped_paths(self):
        for separator in [chr(92),chr(92)*2,'/']:
            raw=('Q:'+separator+'profile'+separator+'private').encode()
            self.assertTrue(findings('log.txt',raw))

    def test_project_paths_and_public_urls(self):
        for raw in [b'adaptive-planner/planner-app/src',b'https://example.org/docs',b'http://maven.apache.org/POM/4.0.0']:
            self.assertEqual([],findings('README.md',raw))

    def test_token_values_are_never_reported(self):
        token=('gh'+'p_'+'a'*32).encode()
        result=findings('config.txt',token)
        self.assertTrue(result)
        self.assertNotIn(token.decode(),str(result))

    def test_private_files(self):
        self.assertTrue(findings('.env',b''))
        self.assertTrue(findings('client.p12',b''))
        self.assertEqual([],findings('.env.example',b'API_KEY='))

if __name__=='__main__':unittest.main()
