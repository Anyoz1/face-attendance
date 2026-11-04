def pytest_addoption(parser):
    parser.addoption('--face-test-image', default=None)
    parser.addoption('--face-test-other-image', default=None)
