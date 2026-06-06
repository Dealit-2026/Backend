import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: Number(__ENV.VUS || 10),
  duration: __ENV.DURATION || '30s',
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const TOKEN = __ENV.TOKEN;
const PRODUCT_ID = __ENV.PRODUCT_ID;
const IMAGE_ID = __ENV.IMAGE_ID;
const CATEGORY_ID = Number(__ENV.CATEGORY_ID || 19);

if (!TOKEN || !PRODUCT_ID || !IMAGE_ID) {
  throw new Error('TOKEN, PRODUCT_ID, and IMAGE_ID env vars are required.');
}

export default function () {
  const payload = JSON.stringify({
    name: `k6 updated product ${__VU}-${__ITER}`,
    description: 'k6 OpenSearch indexing latency measurement',
    categoryId: CATEGORY_ID,
    price: 30000,
    allowOffer: false,
    location: 'Seoul',
    images: [
      {
        imageId: Number(IMAGE_ID),
        imageUrl: 'http://localhost:8080/uploads/product/images/test-image.jpg',
        sortOrder: 1,
      },
    ],
  });

  const response = http.patch(`${BASE_URL}/api/v1/products/${PRODUCT_ID}`, payload, {
    headers: {
      Authorization: `Bearer ${TOKEN}`,
      'Content-Type': 'application/json',
    },
  });

  check(response, {
    'status is 200': (r) => r.status === 200,
  });

  sleep(1);
}
