# TVStoreX
[![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](https://www.apache.org/licenses/LICENSE-2.0.html)

[TVStoreX](https://github.com/IGinX-THU/TVStoreX) is TVStore enhanced with the middleware system [IginX](https://github.com/thulab/IginX). TVStore is a time-series store that can automatically bound storage space to a user-provided size. It is capable of constraining storage costs as large volumes of data keep flowing into a single database instance at a high speed.

TVStore incorporates a novel *time-varying compression* framework to compress older data by higher ratios and newer data by lower ratios, while maintaining high accuracy in many workloads. Its decisions on *storage bounding* are properly chosen based on tight theoretical bounds to minimize information loss and computation cost. The implementation of TVStore builds upon Apache IoTDB. For details see our paper at FAST'22:
* Yanzhe An, Yue Su, Yuqing Zhu, and Jianmin Wang. **"TVStore: Automatically Bounding Time Series Storage via Time-Varying Compression."** In 20th USENIX Conference on File and Storage Technologies (FAST 22). 2022. [[pdf](https://www.usenix.org/conference/fast22/technical-sessions)]  

TVStore is extensively evaluated using [TVStore-benchmark](https://github.com/thulab/TVStore-benchmark). Along the same lines, the authors have also the following related works:
* [IginX](https://github.com/thulab/IginX): an open-source clustering system for multi-dimensional scaling of standalone time series databases, e.g., TVStore
* [Record-breaking benchmarking](https://link.springer.com/chapter/10.1007/978-3-030-94437-7_2): tuning a cluster of IginX and IoTDB(by a special branch) instances to get [a record-breaking result](https://arxiv.org/abs/2107.09351) in the TPCx-IoT benchmarking tests

## Awards

TVStoreX won the [Gold medal in the Geneva International Exhibition of Inventions](https://github.com/IGinX-THU/TVStoreX/blob/main/docs/award.jpg).

<img src="https://github.com/IGinX-THU/TVStoreX/blob/main/docs/award.jpg" width = "210" height = "297" alt="The Gold Medal for TVStoreX" align=center />

## Other Info

For further usage information on the system, please refer to the [Quick Start](https://github.com/IGinX-THU/TVStoreX/blob/main/docs/quickStart.md) of TVStore and [that](https://github.com/thulab/IginX) of IginX.
