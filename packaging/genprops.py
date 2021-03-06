#!/usr/bin/python
#
# Copyright (c) 2012-2013 EMC Corporation
# All Rights Reserved
#
# This software contains the intellectual property of EMC Corporation
# or is licensed to EMC Corporation from third parties.  Use of this
# software and the intellectual property contained therein is expressly
# limited to the terms and conditions of the License Agreement under which
# it is provided by or on behalf of EMC.
#

import sys
import os
import re

def fatal(msg, e = None):
    s  = "Error: " + str(msg);
    if e:
        s += ": (" + str(e) + ")"
    s += "\n"
    sys.stderr.write(s)
    sys.exit(1)

# Read file
def readfile(path):
    if path == "-":
        return '[]'
    try:
        f = open(path, 'r')
        return f.read()
    except Exception, e:
        fatal("Failed to read from file: ' + path", e)
    finally:
        if f:
            f.close()

# Auto-indent a list of lines (incomplete but it works here)
def indent(lines, depth = 0, tab = "\t"):
    results = []
    for line in lines:
        s = line.strip()
        if s.startswith("</") and s != "</foreach>":
            depth -= 1
        results.append(tab*depth + line)
        if not s.startswith("</") and not s.endswith("/>") and not s.startswith("<foreach ") and not re.match("<\S+>[^<>]*</\S+>", s):
            depth +=1
    return results

def lines2text(lines):
    return "\n".join(lines + [""])

# Read and evaluate property definition file
def getprops(path):
    _keys = ( "key", "label", "description", "type", "minLen", "maxLen", "allowedValues", "tag", "advanced", "hidden", "userMutable", "userConfigurable", "rebootRequired", "reconfigRequired", "value", "controlNodeOnly", "notifiers" )
    _types = ( "uint8", "int8", "uint16", "int16", "uint32", "int32", "uint64", "int64", "real32", "real64", "string", "boolean" )
    _bools = ( False, True )
    _globals = {}
    _globals.update(zip(_keys, _keys))
    _globals.update(zip(_types, _types))
    _globals.update(zip(map(lambda x: str(x).lower(), _bools), _bools))

    try:
        return eval(readfile(path), _globals, {})
    except Exception, e:
        fatal("Invalid property definition file " + repr(path), e)

# Expand ${var} for var=value defined on the command line
def expand_vars(arg, vars):
    arg = source_files(arg)
    for var in vars:
        arg = arg.replace("${" + var + "}", vars[var])
    return replace_newline(arg)

# Source in external files denoted by the @{} notation
def source_files(arg):
    m = _src_pattern.match(arg)
    if m is not None:
        src_file_name = _parent_dir + os.sep + m.group(1)
        try:
            with open(src_file_name, 'r') as src_file:
                content = src_file.read().strip()
        except Exception, e:
            fatal("Failed to read from source file " + src_file_name, e)
        arg = arg.replace("@{" + m.group(1) + "}", content)
    return arg

# The newlines in the external file need to be replaced by "\\n"s
# so that: a) it remains a one-liner in the /etc/systool --getprops output
# b) when _get_prop2 is called in /etc/genconfig, they can be converted back
# to actual newlines.
# The reason we must use "\\n" instead of "\n" here is that _get_props2 references
# ${_GENCONFIG_PROPS} without the double quotes
def replace_newline(arg):
    return arg.replace('\n', '\\\\n')

# Fix label or description for "standalone", capitalize the first character
def fix_label(q):
    q = q.replace("server standalone", "standalone server")
    q = q[:1].upper() + q[1:]
    return q

# gentmpl loop unrolling directive
FOREACH_START      = '<foreach iterator="iter">'
FOREACH_END        = '</foreach>'

# XML header
BEANS_HEADER       = \
       '<?xml version="1.0" encoding="UTF-8"?>\n\n'                                                  \
       "<!-- *** DO NOT EDIT THIS FILE ***: This file was generated using %-10r *** -->\n"           \
       "<!-- *** Please read packaging/README.properties                                *** -->\n\n" \
       % os.path.basename(sys.argv[0])

# Spring beans
BEANS_START        = \
       '<beans xmlns="http://www.springframework.org/schema/beans"\n' + \
       '\t  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"\n' + \
       '\t  xsi:schemaLocation="http://www.springframework.org/schema/beans http://www.springframework.org/schema/beans/spring-beans.xsd">'
BEANS_END          = '</beans>'

# Property metadata entry
ENTRY_START        = '<bean id="$1" class="com.emc.storageos.model.property.PropertyMetadata">'
ENTRY_PROPERTY     = '<property name="$1" value="$2" />'
ENTRY_END          = '</bean>'

# Property metadata map
METAMAP_START      = '<bean id="metadata" class="com.emc.storageos.model.property.PropertiesMetadata">'
METAMAP_NAME_START = '<property name="metadata">'
METAMAP_MAP_START  = '<map>'
METAMAP_MAP_ENTRY  = '<entry key="$1" value-ref="$2" />'
METAMAP_NAME_END   = '</property>'
METAMAP_MAP_END    = '</map>'
METAMAP_END        = '</bean>'

# Valid fields of the property metadata class
BEAN_KEYS = ( "key", "label", "description", "type", "minLen", "maxLen", "allowedValues", "tag", "hidden", "advanced", "userMutable", "userConfigurable", "reconfigRequired", "rebootRequired", "value", "controlNodeOnly", "notifiers")


def emit_ovf(ovf_props, config_props, props_vars, pattern, subst, loop):
    def ovf_encode(s, qdollar = True):
        special = { '&' : '&amp;', '<' : '&lt;', '>' : '&gt;', '\'' : '&apos;', '"' : '&quot;', '\n' : '&#10;' }
        return "".join(map(lambda c: special[c] if c in special else c, s)).replace(pattern, subst)

    def ovf_quote(value):
        if isinstance(value, str):
            return '"' + ovf_encode(value) + '"'
        elif isinstance(value, bool) or isinstance(value, int):
            return '"' + str(value).lower() + '"'
        elif hasattr(value, "__iter__"):
            return ",".join(map(ovf_quote, value))
        else:
            raise Exception("Unknown type: " + repr(value))

    def emit_key(prop):
        return ' ovf:key=' + ovf_quote(prop["key"])

    def emit_type(p):
        q = re.sub("^int", "sint", prop["type"])
        if q == "ipaddr":
            return ' ovf:type="string" vmw:qualifiers="Ip"'
        elif q == "ipv6addr":
            return ' ovf:type="string"'
        elif q == 'email':
            return ' ovf:type="string"'
        elif q == "url":
            return ' ovf:type="string"'
        elif q == "hostname":
            return ' ovf:type="string"'
        elif q == 'license':
            return ' ovf:type="string"'
        elif q == 'iplist':
            return ' ovf:type="string"'
        elif q == 'encryptedstring':
            return ' ovf:type="string"'
        elif q == 'text':
            return ' ovf:type="string"'
        elif q == 'encryptedtext':
            return ' ovf:type="string"'
        elif q == 'percent':
            return ' ovf:type="uint8"'
        else:
            return ' ovf:type=' + ovf_quote(q)

    def emit_qualifiers(prop):
        s = ''
        for p in ( "minLen", "maxLen", "allowedValues", ):
            if not p in prop:
                continue
            q = prop[p]
            if s:
                s += ','
            if p == "minLen" or p == "maxLen":
                s += p[0].upper() + p[1:] + "(" + str(q) + ")"
            elif p == "allowedValues":
                s += "ValueMap{" + ovf_quote(q) + "}"
            else:
                raise Exception("Unknown qualifier: " + "(" + str(q) + ")")
        return ' ovf:qualifiers=' + ovf_quote(s) if s else ''    

    def emit_userconf(prop):
        return '  ovf:userConfigurable="true"' if "userConfigurable" in prop and prop["userConfigurable"] else ''

    def emit_value(prop, vars):
        return ' ovf:value=' + ovf_quote(expand_vars(prop["value"], vars)) if "value" in prop else ''

    def emit_property(lines, prop, vars):
        lines.append('<Property' + emit_key(prop) + emit_type(prop) + emit_qualifiers(prop) + emit_userconf(prop) + emit_value(prop, vars) + '>')
        for k in ( "label", "description" ):
             if k in prop:
                 p = ovf_encode(prop[k])
                 p = fix_label(p)
                 lines.append('<' + k.capitalize() + '>' + p + '</' + k.capitalize() + '>')
        lines.append('</Property>')

    lines = []
    for prop in ovf_props:
        if "${iter}" in prop["key"] and loop:
            lines.append(FOREACH_START)
            emit_property(lines, prop, props_vars)
            lines.append(FOREACH_END)
        else:
            emit_property(lines, prop, props_vars)
    return lines2text(indent(lines, 3, "  "))

def emit_config_defaults(ovf_props, config_props, props_vars, pattern, subst, loop):
    lines = []

    for prop in config_props:
        if 'value' in prop:
            lines.append(prop["key"].replace(pattern, subst) + '=' + expand_vars(prop["value"], props_vars))

    lines = sorted(lines)
    return lines2text(lines)

def emit_ovf_defaults(ovf_props, controller_props, props_vars, pattern, subst, loop):
    lines = []

    for prop in ovf_props:
        if 'value' in prop:
            lines.append(prop["key"].replace(pattern, subst) + '=' + expand_vars(prop["value"], props_vars))
    for prop in controller_props:
        if 'controlNodeOnly' in prop and not prop["controlNodeOnly"]:
            lines.append(prop["key"] + '=' + expand_vars(prop["value"], props_vars))
                 
    lines = sorted(lines)
    return lines2text(lines)

# Generate Spring Framework bean with a map of properties metadata
def emit_bean(ovf_props, config_props, props_vars, pattern, subst, loop):
    props = ovf_props + config_props
    def bean_encode(s, qdollar = True):
        # bean_encode is basically the same with ovf_encode except that '\n' is converted to '\\n' for later plain text use. e.g. config.properties
        special = { '&' : '&amp;', '<' : '&lt;', '>' : '&gt;', '\'' : '&apos;', '"' : '&quot;', '\n' : '\\n' }
        return "".join(map(lambda c: special[c] if c in special else c, s)).replace(pattern, subst)

    def bean_repr(value):
        if isinstance(value, str):
            return value
        elif isinstance(value, bool) or isinstance(value, int):
            return str(value).lower()
        elif hasattr(value, "__iter__"):
            return ",".join(map(bean_repr, value))
        else:
            raise Exception("Unsupported type found in config file")

    def emit_bean_entry(lines, ids, prop, pattern, subst, vars):
        for p in BEAN_KEYS:
            if p == "key":
                q = prop[p].replace(pattern, subst)
                ids.append(q)
                lines.append(ENTRY_START.replace('$1', q))
            elif p in prop:
                q = bean_repr(prop[p]).replace(pattern, subst)
                if p in ( "label", "description" ):
                    q = fix_label(q)
                if p == "value":
                    q = bean_encode(expand_vars(q, vars))
                lines.append(ENTRY_PROPERTY.replace('$1', p).replace('$2', q))
        lines.append(ENTRY_END)

    lines =[]
    ids_iterable = []
    ids_simple = []
    ids_props = []
    lines.append(BEANS_START)
    for prop in props:
        if "${iter}" in prop["key"] and loop:
            lines.append(FOREACH_START)
            emit_bean_entry(lines, ids_props, prop, pattern, subst, props_vars)
            lines.append(FOREACH_END)
        else:
            emit_bean_entry(lines, ids_props, prop, pattern, subst, props_vars)
    lines.append(METAMAP_START)
    lines.append(METAMAP_NAME_START)
    lines.append(METAMAP_MAP_START)
    for id in ids_props:
        if "${iter}" in id and loop:
            lines.append(FOREACH_START)
            lines.append(METAMAP_MAP_ENTRY.replace('$1', id).replace('$2', id))
            lines.append(FOREACH_END)
        else:
            lines.append(METAMAP_MAP_ENTRY.replace('$1', id).replace('$2', id))
    lines.append(METAMAP_MAP_END)
    lines.append(METAMAP_NAME_END)
    lines.append(METAMAP_END)
    lines.append(BEANS_END)
    return BEANS_HEADER + lines2text(indent(lines, 0, "\t"))

if __name__ == "__main__":
    methods = {
        "--ovf-standalone-properties"  : ( emit_ovf,              ( "${iter}", "standalone",  False ) ),
        "--ovf-cluster-properties"     : ( emit_ovf,              ( "${iter}", "${iter}",     True  ) ),
        "--config-defaults"            : ( emit_config_defaults,  ( "${iter}", "standalone",  False ) ),
        "--ovf-controller-defaults"    : ( emit_ovf_defaults,     ( "${iter}", "${iter}",     True  ) ),
        "--metadata-var"               : ( emit_bean,             ( "${iter}", "standalone",  False ) ),
        "--metadata-var-template"      : ( emit_bean,             ( "${iter}", "${iter}",     True  ) ),
    }

    def usage():
        s  = "Usage: " + sys.argv[0] + " option ovf-env.def config.def [var1=value1 var2=value2 ...]\n"
        s += "Options:\n"
        s += "".join(map(lambda k: "    " + k + "\n", sorted(methods.keys())))
        sys.stderr.write(s)
        sys.exit(2)

    if len(sys.argv) < 4 or sys.argv[1] not in methods:
       usage()

    try:
        f, args        = methods[sys.argv[1]]
        ovf_props      = getprops(sys.argv[2])
        config_props   = getprops(sys.argv[3])
        props_vars     = dict(map(lambda arg: tuple(arg.split('=',1)), sys.argv[4:]))
        _src_pattern   = re.compile('.*@{(.*)}.*')
        _parent_dir    = os.path.dirname(os.path.realpath(__file__))
        sys.stdout.write(f(ovf_props, config_props, props_vars, *args))
        sys.exit(0)
    except Exception, e:
        #import traceback; traceback.print_exc()
        fatal(e)





