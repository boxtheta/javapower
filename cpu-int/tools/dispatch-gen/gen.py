# Generate the inline-dispatch block of Interpreter.java from the hot list and the registrations.
import re, sys
D='/Users/mxavier/code/javapower/cpu-int/src/main/java/org/boxtheta/javapower/cpu/'
refs={}
for f in ['Alu','LoadStore','BranchInsns','SystemInsns']:
    src=open(D+f+'.java').read()
    for m in re.finditer(r'(?:t\.add\w*|Fields\.add\w*)\(([^;]*?)"([^"]+)",\s*([\w.]+::\w+|Fields\.INVALID)\)', src):
        refs[m.group(2)]=m.group(3)
hot=open(sys.argv[1]).read().split()
NHOT=int(sys.argv[2])
seen=set()
for h in hot:
    assert h in refs, h
    assert h not in seen, h
    seen.add(h)
def const(n): return 'K_'+n.upper().replace('.','_DOT')
consts=[]; names=[]; cases=[]; handlers=[]
for i,h in enumerate(hot,1):
    c=const(h); cls,meth=refs[h].split('::')
    consts.append(f'    private static final int {c} = {i};')
    names.append(f'"{h}"')
    handlers.append(f'"{cls}.{meth}"')
    cases.append((f'                    case {c} -> {cls}.{meth}(s, insn);' if i <= NHOT else f'            case {c} -> {cls}.{meth}(s, insn);'))
out=open(D+'Interpreter.java').read()
def rep(tag, body):
    global out
    a=out.index(f'// BEGIN GENERATED {tag}'); a=out.index('\n',a)+1
    b=out.index(f'// END GENERATED {tag}'); b=out.rindex('\n',0,b)+1
    out=out[:a]+body+'\n'+out[b:]
rep('CONSTANTS', '\n'.join(consts + [f'    static final int NUM_INLINE = {len(hot)};']))
# names array, wrapped
lines=[]; cur='        null'
for n in names:
    if len(cur)+len(n)+2>100: lines.append(cur+','); cur='        '+n
    else: cur+=', '+n
lines.append(cur)
rep('NAMES', '\n'.join(lines))
lines=[]; cur='        null'
for n in handlers:
    if len(cur)+len(n)+2>100: lines.append(cur+','); cur='        '+n
    else: cur+=', '+n
lines.append(cur)
rep('HANDLERS', '\n'.join(lines))
rep('CASES', '\n'.join(cases[:NHOT]))

open(D+'Interpreter.java','w').write(out)
print(len(hot),'inline handlers')
