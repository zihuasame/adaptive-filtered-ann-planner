"""Local-only publication checks. Findings report locations/rules, never credential values."""
from pathlib import Path
import re

TEXT_SUFFIXES={'.java','.py','.ps1','.cmd','.sh','.md','.txt','.log','.json','.yaml','.yml','.xml','.properties','.ini','.conf','.env','.toml','.csv'}
PRIVATE_NAMES={'.env','credentials.json','id_rsa','id_ed25519','settings-security.xml'}
RULES={
    'absolute_windows_path':re.compile(r'(?i)(?<![a-z0-9])[a-z]:[\\/]+(?!/)'),
    'home_directory':re.compile(r'(?i)/(?:Users|home)/[^/\s]+'),
    'private_key':re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----'),
    'provider_token':re.compile(r'\b(?:ghp_|github_pat_|glpat-|xox[baprs]-|sk-proj-|sk-ant-)[A-Za-z0-9_-]{12,}'),
    'provider_secret_key':re.compile(r'\bsk-[A-Za-z0-9]{24,}\b'),
    'aws_access_key':re.compile(r'\b(?:AKIA|ASIA)[A-Z0-9]{16}\b'),
    'google_api_key':re.compile(r'\bAIza[A-Za-z0-9_-]{30,}\b'),
    'bearer_literal':re.compile(r'(?i)\bBearer\s+[A-Za-z0-9_.~-]{24,}'),
    'url_embedded_password':re.compile(r'(?i)\b(?:https?|postgres(?:ql)?|mysql|mongodb(?:\+srv)?|redis)://[^\s/@:]+:[^\s/@]+@'),
    'assigned_credential':re.compile(r'''(?ix)\b(?:api[_-]?key|access[_-]?token|client[_-]?secret|password)\s*[=:]\s*["']([A-Za-z0-9_+/.=-]{16,})["']'''),
}

def findings(name,raw):
    path=Path(name)
    if path.name.lower() in PRIVATE_NAMES or path.suffix.lower() in {'.p12','.pfx','.jks','.keystore','.key'}:
        return [dict(path=name,line=None,rule='private_credential_file')]
    if path.suffix.lower() not in TEXT_SUFFIXES and path.name not in {'.gitignore','mvnw'}:
        return []
    try:text=raw.decode('utf-8-sig')
    except UnicodeError:return []
    result=[]
    for line_no,line in enumerate(text.splitlines(),1):
        for rule,pattern in RULES.items():
            if pattern.search(line):result.append(dict(path=name,line=line_no,rule=rule))
    return result

def assert_clean(files):
    problems=[]
    for name,raw in files:problems.extend(findings(name,raw))
    if problems:
        import json
        raise ValueError('Publication privacy scan failed (values withheld): '+json.dumps(problems[:30]))
