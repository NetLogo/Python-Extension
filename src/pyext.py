#!/usr/bin/env python

import os
import socket
import sys
import numbers
import json

if sys.version_info[0] > 2 and sys.version_info[1] > 3:
    from collections.abc import Mapping
else:
    from collections import Mapping

import traceback
import struct


LEN_SIZE = 10
TYPE_SIZE = 1

# In
STMT_MSG = 0
EXPR_MSG = 1
ASSN_MSG = 2

# Out

SUCC_MSG = 0
ERR_MSG = 1


def print_err(s):
    sys.stderr.write('{}\n'.format(s))
    sys.stderr.flush()


class ConnectionReader(object):
    def __init__(self, conn):
        self.conn = conn
        self.buff = bytearray()

    def _get_packet(self):
        data = self.conn.recv(1024)
        if not data:
            raise EOFError('Connection closed')
        return data

    def read(self, size):
        while len(self.buff) < size:
            self.buff.extend(self._get_packet())
        result = bytes(self.buff[:size])
        del self.buff[:size]
        return result

    def read_json(self):
        return json.loads(self.read_string())

    def read_int(self):
        return struct.unpack('>i', self.read(4))[0]

    def read_byte(self):
        return struct.unpack('b', self.read(1))[0]

    def read_string(self):
        length = self.read_int()
        return self.read(length).decode('utf-8')


class ConnectionWriter(object):
    def __init__(self, conn):
        self.conn = conn
        self.buff = bytearray()

    def write(self, data):
        self.buff.extend(data)

    def write_byte(self, b):
        self.write(struct.pack('b', b))

    def write_int(self, i):
        self.write(struct.pack('>i', i))

    def write_string(self, s):
        bs = to_bytes(s)
        self.write_int(len(bs))
        self.write(bs)

    def flush(self):
        self.conn.sendall(self.buff)
        self.clear()

    def clear(self):
        self.buff = bytearray()


def utf8(bs):
    if sys.version_info >= (3, 0):
        return str(bs, 'UTF8')
    else:
        return unicode(bs, 'UTF8')


def to_bytes(s):
    if sys.version_info >= (3, 0):
        return bytes(s, 'UTF8')
    else:
        return bytes(s)


class FlexibleEncoder(json.JSONEncoder):
    def default(self, o):
        if isinstance(o, numbers.Integral):
            return int(o)
        if isinstance(o, numbers.Number):
            return float(o)
        if isinstance(o, Mapping):
            return dict(o)
        elif hasattr(o, '__len__') and hasattr(o, '__iter__'):
            return list(o)
        else:
            return json.JSONEncoder.default(self, o)  # let it error


def logo_responder():
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        sock.bind(('localhost', 0))
        sock.listen(0)
        _, port = sock.getsockname()
        sys.stdout.write("{}\n".format(port))
        sys.stdout.flush()
        conn, addr = sock.accept()
        try:
            inp = ConnectionReader(conn)
            out = ConnectionWriter(conn)
            encoder = FlexibleEncoder()
            globs = {}
            while True:
                msg_type = inp.read_byte()
                try:
                    if msg_type == STMT_MSG:
                        code = inp.read_string()
                        exec(code, globs)
                        out.write_byte(SUCC_MSG)
                    elif msg_type == EXPR_MSG:
                        code = inp.read_string()
                        result = encoder.encode(eval(code, globs))
                        out.write_byte(SUCC_MSG)
                        out.write_string(result)
                    elif msg_type == ASSN_MSG:
                        var = inp.read_string()
                        val = json.loads(inp.read_string())
                        globs[var] = val
                        out.write_byte(SUCC_MSG)
                    else:
                        raise Exception('Unrecognized message type: {}'.format(msg_type))
                except Exception as e:
                    out.write_byte(ERR_MSG)
                    out.write_string(str(e))
                    out.write_string(traceback.format_exc())
                finally:
                    out.flush()
                    flush()
        finally:
            conn.close()
    finally:
        sock.close()


def flush():
    sys.stdout.flush()
    sys.stderr.flush()


if __name__ == '__main__':
    sys.path.insert(0, os.getcwd())
    logo_responder()
