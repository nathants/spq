import requests
import uuid
import json
import time
import pool.proc
import argh
import os


os.chdir(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def _take(queue_name, url, parallelism):
    start = time.time()
    def worker():
        while True:
            resp = requests.post('%s/take?queue=%s&timeout-millis=100' % (url, queue_name))
            if resp.status_code != 200:
                break
    procs = [pool.proc.new(worker) for _ in range(parallelism)]
    for proc in procs:
        proc.join()
    seconds = time.time() - start
    stats = requests.get('%s/stats' % url).json()
    return '%s takes/second\n' % int(stats[queue_name]['num-queued'] / float(seconds))


def _put(queue_name, url, seconds, parallelism):
    def worker():
        while True:
            resp = requests.post('%s/put?queue=%s' % (url, queue_name), data=json.dumps({'item': 'foo', 'data': 'asdfasdf' * 10}))
            assert resp.status_code == 200
    stats = requests.get('%s/stats' % url).json()
    procs = [pool.proc.new(worker) for _ in range(parallelism)]
    time.sleep(seconds)
    for proc in procs:
        proc.terminate()
    new_stats = requests.get('%s/stats' % url).json()
    total_put = new_stats[queue_name]['num-queued'] - stats.get(queue_name, {}).get('num-queued', 0)
    res = 'put %s items\n' % total_put
    return res + '%s puts/second\n' % int(total_put / float(seconds))


def main(url: 'spq url, http://localhost:8080 or something',
         seconds: 'number of seconds to furiously /put data' = 10,
         parallelism: 'number of workers to /put and /take from' = 10):
    """
    start spq with something like: lein trampoline run -m spq.server/-main 8080
    """
    queue_name = ('benchmark_%s' % uuid.uuid4()).replace('-', '_')
    res = _put(queue_name, url, seconds, parallelism)
    res += _take(queue_name, url, parallelism)
    print(res)


if __name__ == '__main__':
    argh.dispatch_command(main)
